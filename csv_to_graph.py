import sys
import pandas as pd
import plotly.express as px

input_file = sys.argv[1]
output_file = sys.argv[2]

df = pd.read_csv(input_file)
fig = px.line(df, x = 'Elapsed time (s)', y = 'Requests per second')
fig.write_html(output_file)
