package cloud.trotter.census.server.ops

/** The sole stylesheet for operator HTML; never interpolate display data into CSS. */
object OpsStyles {
    const val CSS = """
:root {
  color-scheme:dark;
  --bg:#0C1014; --surface:#141A21; --surface2:#1B2530; --surface3:#243240;
  --line:rgba(255,255,255,.08); --line-strong:rgba(255,255,255,.16);
  --text:#EEF3F7; --text2:#AAB6C2; --text3:#6B7886;
  --accent:#46E0C8; --accent-dim:#1F4D49; --accent-text:#052B27;
  --good:#3DDC84; --warn:#FFC24B; --bad:#FF6B6B; --neutral:#93A0AD;
  --good-bg:rgba(61,220,132,.14); --warn-bg:rgba(255,194,75,.14);
  --bad-bg:rgba(255,93,93,.14); --neutral-bg:rgba(139,151,164,.14);
  --body-font:system-ui,-apple-system,"Segoe UI",Roboto,Arial,sans-serif;
  --number-font:system-ui,-apple-system,"Segoe UI",Roboto,Arial,sans-serif;
  --mono-font:ui-monospace,"SFMono-Regular",Consolas,"Liberation Mono",monospace;
}
@media (prefers-color-scheme: light) {
  :root {
    color-scheme:light;
    --bg:#EEF2F4; --surface:#FFFFFF; --surface2:#F3F6F8; --surface3:#E7EDF1;
    --line:rgba(12,16,20,.10); --line-strong:rgba(12,16,20,.20);
    --text:#18232D; --text2:#526170; --text3:#71808E;
    --accent:#096D61; --accent-dim:#DCF5EE; --accent-text:#052B27;
    --good:#176B40; --warn:#805300; --bad:#B72C38; --neutral:#526170;
  }
}
* { box-sizing:border-box; }
html { font-size:16px; }
body { margin:0; background:var(--bg); color:var(--text); font-family:var(--body-font); font-size:1rem; line-height:1.5; font-variant-numeric:tabular-nums lining-nums; }
a { color:var(--accent); text-underline-offset:.2em; overflow-wrap:anywhere; }
a:hover { text-decoration-thickness:2px; }
:focus-visible { outline:3px solid var(--accent); outline-offset:4px; }
h1,h2,h3,h4,p,dl { margin:0; }
h1 { font-size:2rem; line-height:1.15; letter-spacing:-.035em; }
h2 { font-size:1.25rem; line-height:1.3; letter-spacing:-.015em; }
h3 { font-size:1.0625rem; line-height:1.4; }
h4 { font-size:1rem; }
code,pre { font-family:var(--mono-font); font-size:.875em; }
code { overflow-wrap:anywhere; }
time { white-space:nowrap; }
button,input,select,textarea { font:inherit; }
button,input { min-height:44px; padding:.5rem; color:var(--text); background:var(--surface2); border:1px solid var(--line-strong); border-radius:.25rem; }
button { cursor:pointer; }
label { display:block; margin:1rem 0; }
input { display:block; width:100%; }
.login { max-width:30rem; }
.shell { width:min(100%,90rem); margin-inline:auto; padding:2rem; }
.page-header { display:grid; gap:.75rem; margin-bottom:1.5rem; }
.eyebrow { color:var(--text2); font-size:.75rem; font-weight:700; letter-spacing:.12em; }
.header-meta { display:flex; flex-wrap:wrap; gap:.5rem 1.5rem; }
.header-meta div { display:flex; align-items:baseline; gap:.5rem; }
dt,.muted,caption,.metric-label,.cell-label { color:var(--text2); }
dd { margin:0; }
main { display:grid; gap:1.5rem; min-width:0; }
.metrics { display:grid; grid-template-columns:repeat(4,minmax(0,1fr)); gap:.75rem; }
.metric { display:grid; align-content:start; gap:.5rem; padding:1rem; color:var(--text); background:var(--surface); border:1px solid var(--line); border-radius:.75rem; text-decoration:none; min-width:0; }
.metric:hover { border-color:var(--accent); }
.metric-label { font-size:.875rem; }
.metric-value { font-family:var(--number-font); font-size:1.75rem; line-height:1.2; font-weight:650; overflow-wrap:anywhere; }
.metric-value.date { font-size:1.125rem; }
.section-nav { display:flex; flex-wrap:wrap; gap:.25rem .75rem; }
.section-nav a,.action,.cluster-link { display:inline-flex; align-items:center; min-height:44px; }
.section-nav a { padding:.25rem .5rem; }
.status-filter { display:flex; flex-wrap:wrap; gap:.5rem; }
.status-filter a.current { background:var(--accent-dim); color:var(--accent); }
.pager { display:flex; gap:1rem; align-items:center; flex-wrap:wrap; }
.panel { min-width:0; padding:1.5rem; background:var(--surface); border:1px solid var(--line); border-radius:1rem; }
.panel > * + * { margin-top:1rem; }
.section-heading,.cluster-heading { display:flex; align-items:center; flex-wrap:wrap; gap:.5rem 1rem; }
.section-heading h2,.cluster-heading h4 { margin-right:auto; }
.chip { display:inline-flex; align-items:center; gap:.25rem; padding:.15rem .5rem; border:1px solid transparent; border-radius:999px; font-size:.875rem; line-height:1.5; font-weight:650; overflow-wrap:anywhere; max-width:100%; }
.good { color:var(--good); background:var(--good-bg); }
.warn { color:var(--warn); background:var(--warn-bg); }
.bad { color:var(--bad); background:var(--bad-bg); }
.neutral { color:var(--neutral); background:var(--neutral-bg); }
.accent { color:var(--accent); background:var(--accent-dim); }
.empty { padding:1rem; color:var(--text2); border:1px dashed var(--line-strong); border-radius:.5rem; }
.notice { padding:1rem; background:var(--surface2); border-left:3px solid var(--accent); border-radius:.25rem; }
details { border-top:1px solid var(--line); padding-top:.5rem; }
summary { cursor:pointer; min-height:44px; padding:.625rem 0; font-weight:600; }
details[open] > summary { margin-bottom:.5rem; }
details > p { margin-bottom:.75rem; }
.data-table { width:100%; table-layout:fixed; border-collapse:collapse; font-size:.875rem; }
caption { text-align:left; padding:0 0 .75rem; font-size:.875rem; }
.data-table th,.data-table td { padding:.75rem; text-align:left; vertical-align:top; border-bottom:1px solid var(--line); overflow-wrap:anywhere; }
.data-table thead th { background:var(--surface2); color:var(--text2); font-weight:600; }
.data-table tbody th { font-weight:500; }
.data-table tbody tr:last-child > * { border-bottom:0; }
.data-table .num { text-align:right; }
.data-table code { font-size:1em; }
.num { font-family:var(--number-font); font-variant-numeric:tabular-nums lining-nums; }
.cell-label { display:none; }
.cell-value { min-width:0; overflow-wrap:anywhere; }
.cell-value ul { padding-left:1.1rem; margin:.25rem 0; }
.cluster-grid { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:1rem; }
.cluster-card { min-width:0; background:var(--surface2); border:1px solid var(--line); border-radius:.75rem; padding:1rem; }
.cluster-card > * + * { margin-top:.75rem; }
.facts { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:.75rem 1rem; }
.facts div { min-width:0; }
.facts dt { font-size:.8125rem; }
.facts dd { margin-top:.2rem; overflow-wrap:anywhere; }
.facts .wide { grid-column:1/-1; }
.versions,.rule-list { display:flex; flex-wrap:wrap; gap:.35rem .75rem; list-style:none; padding:0; margin:0; }
.totals { display:grid; grid-template-columns:repeat(5,minmax(0,1fr)); gap:1rem; }
.totals > div { min-width:0; overflow-wrap:anywhere; }
.totals dt { font-size:.8125rem; }
.totals dd { margin-top:.25rem; }
.notes { white-space:pre-wrap; overflow-wrap:anywhere; }
.sample + .sample { border-top:1px solid var(--line); padding-top:1rem; margin-top:1rem; }
.sample > * + * { margin-top:.5rem; }
.tree-wrap { max-width:100%; overflow:auto; padding:.75rem; background:var(--bg); border:1px solid var(--line); border-radius:.5rem; }
.tree,.tree ul { list-style:none; padding:0; margin:0; }
.tree { width:max-content; min-width:100%; }
.tree ul { margin-left:.5rem; padding-left:.875rem; border-left:1px solid var(--text3); }
.tree li { padding:.35rem 0; }
.node-line { display:flex; flex-wrap:wrap; align-items:baseline; gap:.35rem .75rem; max-width:42rem; }
.node-field { color:var(--text2); font-size:.875rem; }
.node-field code { color:var(--text); font-size:1em; }
.node-field .redacted { color:var(--text2); background:var(--surface2); border:1px dashed var(--line-strong); border-radius:.25rem; padding:.1rem .3rem; }
.page-footer { margin-top:2rem; padding-top:1rem; border-top:1px solid var(--line); color:var(--text2); font-size:.875rem; }
.skip-link { position:absolute; left:1rem; top:-10rem; padding:.75rem; background:var(--surface); z-index:1; }
.skip-link:focus { top:1rem; }
.visually-hidden { position:absolute; width:1px; height:1px; padding:0; margin:-1px; overflow:hidden; clip-path:inset(50%); white-space:nowrap; }
@media (max-width:48rem) {
  .shell { padding:1rem; }
  h1 { font-size:1.625rem; }
  main { gap:1rem; }
  .panel { padding:1rem; border-radius:.75rem; }
  .metrics,.totals { grid-template-columns:repeat(2,minmax(0,1fr)); }
  .metric { padding:.875rem; }
  .metric-value { font-size:1.5rem; }
  .cluster-grid,.facts { grid-template-columns:minmax(0,1fr); }
  .data-table,.data-table tbody { display:block; width:100%; }
  .data-table { font-size:1rem; }
  .data-table caption { display:block; }
  .data-table thead { position:absolute; width:1px; height:1px; padding:0; margin:-1px; overflow:hidden; clip-path:inset(50%); white-space:nowrap; }
  .data-table tbody tr { display:block; padding:.75rem; background:var(--surface2); border:1px solid var(--line); border-radius:.5rem; }
  .data-table tbody tr + tr { margin-top:.75rem; }
  .data-table tbody th,.data-table tbody td { display:grid; grid-template-columns:minmax(0,2fr) minmax(0,3fr); gap:.75rem; padding:.35rem 0; border:0; text-align:left; }
  .cell-label { display:block; font-family:var(--body-font); font-size:.875rem; font-weight:400; }
  .data-table .num .cell-label { text-align:left; }
  .data-table .num .cell-value { text-align:right; }
  .tree-wrap { padding:.5rem; }
  .node-line { max-width:100%; }
}
"""
}
