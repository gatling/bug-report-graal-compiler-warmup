package io.gatling.benchmark;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.*;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Client implements AutoCloseable {
  private static final Logger LOGGER = LoggerFactory.getLogger(Client.class);

  public Client(final String hostname, final int port) {
    this.hostname = hostname;
    this.port = port;
  }

  private final String hostname;
  private final int port;

  private final EventLoopGroup group =
      Epoll.isAvailable() ? new EpollEventLoopGroup() : new NioEventLoopGroup();
  private final ScheduledExecutorService executorService = Executors.newScheduledThreadPool(1);
  private final LongAdder longAdder = new LongAdder();

  private volatile boolean stopSignal = false;

  public List<ResultLine> run(int connections, int durationSeconds) throws Exception {
    final CountDownLatch latch = new CountDownLatch(connections);

    final var bootstrap =
        new Bootstrap()
            .group(group)
            .channel(Epoll.isAvailable() ? EpollSocketChannel.class : NioSocketChannel.class);

    final var results = new ConcurrentLinkedQueue<ResultLine>();
    executorService.scheduleAtFixedRate(
        () -> results.add(new ResultLine(System.nanoTime(), longAdder.sumThenReset())),
        0,
        250,
        TimeUnit.MILLISECONDS);

    final var httpDecoderConfig = new HttpDecoderConfig().setValidateHeaders(false);

    for (int i = 0; i < connections; i++) {
      bootstrap
          .clone()
          .handler(
              new ChannelInitializer<>() {
                @Override
                protected void initChannel(Channel ch) {
                  ch.pipeline()
                      .addLast(
                          new HttpClientCodec(httpDecoderConfig, false, false),
                          new HttpContentDecompressor(),
                          new AppHandler(latch));
                }
              })
          .connect(new InetSocketAddress(hostname, port))
          .addListener(
              f -> {
                if (!f.isSuccess()) {
                  LOGGER.error("Connect failure", f.cause());
                  latch.countDown();
                }
              });
    }

    Thread.sleep(durationSeconds * 1000L);
    stopSignal = true;
    if (!latch.await(10, TimeUnit.SECONDS)) {
      LOGGER.warn("Client didn't stop after 10 seconds");
    }

    final var resultsList = results.stream().toList();
    final var durationMs =
        Duration.ofNanos(System.nanoTime() - resultsList.getFirst().elapsedNanos()).toMillis();
    final var requestCount = resultsList.stream().mapToLong(ResultLine::requests).sum();
    final var throughput = (double) requestCount / durationMs * 1000;
    System.out.printf(
        "Performed %d requests in %d ms, avg throughput=%.2f rps",
        requestCount, durationMs, throughput);

    return resultsList;
  }

  @Override
  public void close() throws Exception {
    executorService.close();
    group.shutdownGracefully().sync();
  }

  private final class AppHandler extends SimpleChannelInboundHandler<HttpObject> {

    private AppHandler(CountDownLatch latch) {
      this.latch = latch;
    }

    private final CountDownLatch latch;

    private void sendRequest(ChannelHandlerContext ctx) {
      ctx.writeAndFlush(request());
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
      sendRequest(ctx);
    }

    private FullHttpRequest request() {
      final var request =
          new DefaultFullHttpRequest(
              HttpVersion.HTTP_1_1, HttpMethod.GET, "", Unpooled.EMPTY_BUFFER, false);
      request
          .headers()
          .add(HttpHeaderNames.HOST, hostname)
          .add(
              HttpHeaderNames.ACCEPT,
              "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
          .add(HttpHeaderNames.ACCEPT_LANGUAGE, "en-US,en;q=0.5")
          .add(
              HttpHeaderNames.USER_AGENT,
              "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:109.0) Gecko/20100101 Firefox/119.0")
          .add(HttpHeaderNames.ACCEPT_ENCODING, "gzip, deflate");
      return request;
    }

    @Override
    public void channelRead0(ChannelHandlerContext ctx, HttpObject msg) {
      LOGGER.debug("Received {}", msg);
      if (msg instanceof LastHttpContent) {
        longAdder.increment();
        if (stopSignal) {
          ctx.close();
          latch.countDown();
        } else {
          sendRequest(ctx);
        }
      }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
      if (ctx.channel().isActive()) {
        LOGGER.error("exceptionCaught", cause);
        ctx.close();
        latch.countDown();
      }
    }
  }
}
