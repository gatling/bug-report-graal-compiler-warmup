package io.gatling.benchmark;

import static io.netty.handler.codec.http.HttpHeaderNames.*;
import static io.netty.handler.codec.http.HttpHeaderValues.APPLICATION_JSON;
import static io.netty.handler.codec.http.HttpHeaderValues.GZIP;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Server implements AutoCloseable {
  private static final Logger LOGGER = LoggerFactory.getLogger(Server.class);

  private final int idleTimeoutMillis;
  private final EventLoopGroup bossGroup;
  private final EventLoopGroup workerGroup;
  private final ChannelFuture channelFuture;

  public Server(int port, int idleTimeoutMillis) throws Exception {
    this.idleTimeoutMillis = idleTimeoutMillis;

    final var useEpoll = Epoll.isAvailable();
    final var channelClass =
        useEpoll ? EpollServerSocketChannel.class : NioServerSocketChannel.class;
    bossGroup = useEpoll ? new EpollEventLoopGroup(1) : new NioEventLoopGroup(1);
    workerGroup = useEpoll ? new EpollEventLoopGroup() : new NioEventLoopGroup();

    final var bootstrap =
        new ServerBootstrap()
            .option(ChannelOption.SO_BACKLOG, 15 * 1024)
            .group(bossGroup, workerGroup)
            .channel(channelClass);
    channelFuture =
        bootstrap.childHandler(httpChannelInitializer()).bind(new InetSocketAddress(port)).sync();
  }

  private ChannelInitializer<Channel> httpChannelInitializer() {
    return new ChannelInitializer<>() {
      @Override
      protected void initChannel(Channel ch) {
        ch.pipeline()
            .addLast("idleTimer", new CloseOnIdleReadTimeoutHandler(idleTimeoutMillis))
            .addLast("decoder", new HttpRequestDecoder(4096, 8192, 8192, false))
            .addLast("aggregator", new HttpObjectAggregator(30000))
            .addLast("encoder", new HttpResponseEncoder())
            .addLast(
                "handler",
                new ChannelInboundHandlerAdapter() {
                  @Override
                  public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
                    if (evt instanceof IdleStateEvent
                        && ((IdleStateEvent) evt).state() == IdleState.READER_IDLE) {
                      LOGGER.info("Idle => closing");
                      ctx.close();
                    }
                  }

                  @Override
                  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                    if (cause instanceof IOException) {
                      final var root = cause.getCause() != null ? cause.getCause() : cause;
                      if (!(root.getMessage() != null
                          && root.getMessage().endsWith("Connection reset by peer"))) {
                        // ignore, this is just client aborting socket
                        LOGGER.error("exceptionCaught", cause);
                      }
                      ctx.channel().close();
                    } else {
                      ctx.fireExceptionCaught(cause);
                    }
                  }

                  @Override
                  public void channelRead(ChannelHandlerContext ctx, Object msg) {
                    try {
                      if (msg instanceof FullHttpRequest request) {
                        writeResponse(ctx, request, Content.Json1k);
                      } else {
                        LOGGER.error("Read unexpected msg={}", msg);
                      }
                    } finally {
                      ReferenceCountUtil.release(msg);
                    }
                  }
                });
      }
    };
  }

  private void writeResponse(ChannelHandlerContext ctx, HttpRequest request, Content content) {
    final var acceptGzipHeader = request.headers().get(ACCEPT_ENCODING);
    final var acceptGzip = acceptGzipHeader != null && acceptGzipHeader.contains("gzip");
    final var bytes = acceptGzip ? content.compressedBytes : content.rawBytes;

    final var response =
        new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer(bytes));
    response.headers().set(CONTENT_TYPE, content.contentType).set(CONTENT_LENGTH, bytes.length);
    if (acceptGzip) {
      response.headers().set(CONTENT_ENCODING, GZIP);
    }
    writeResponse(ctx, response);
  }

  private void writeResponse(ChannelHandlerContext ctx, DefaultFullHttpResponse response) {
    response.headers().set(CONTENT_LENGTH, response.content().readableBytes());
    ctx.writeAndFlush(response);
    LOGGER.debug("wrote response {}", response);
  }

  @Override
  public void close() throws InterruptedException {
    workerGroup.shutdownGracefully().sync();
    bossGroup.shutdownGracefully().sync();
    channelFuture.channel().closeFuture().sync();
  }

  private static final class CloseOnIdleReadTimeoutHandler extends IdleStateHandler {
    public CloseOnIdleReadTimeoutHandler(int idleTimeoutMillis) {
      super(idleTimeoutMillis, 65, 65, TimeUnit.MILLISECONDS);
    }

    @Override
    protected void channelIdle(ChannelHandlerContext ctx, IdleStateEvent evt) {
      if (evt.state() == IdleState.READER_IDLE) {
        ctx.close();
      }
    }
  }

  private record Content(
      String path, byte[] rawBytes, byte[] compressedBytes, CharSequence contentType) {
    public static final Content Json1k = Content.from("/json/1k.json", APPLICATION_JSON);

    private static Content from(String path, CharSequence contentType) {
      try {
        var is = Content.class.getClassLoader().getResourceAsStream(path);
        if (is == null) {
          is = Content.class.getClassLoader().getResourceAsStream(path.substring(1));
        }
        if (is == null) {
          throw new IllegalArgumentException(
              "Couldn't locate resource " + path + " in ClassLoader");
        }
        final byte[] rawBytes;
        try {
          rawBytes = is.readAllBytes();
        } finally {
          is.close();
        }

        final byte[] compressedBytes;
        try (final var baos = new ByteArrayOutputStream();
            final var gzip = new GZIPOutputStream(baos); ) {
          gzip.write(rawBytes);
          gzip.close();
          compressedBytes = baos.toByteArray();
        }

        return new Content(path, rawBytes, compressedBytes, contentType);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
  }
}
