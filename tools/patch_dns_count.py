#!/usr/bin/env python3
"""Count the DNS messages sing-box's DNS router answers (libbox/dns/), so the
dashboard can show DNS queries that the core resolves itself (hijack-dns)
instead of only those that became connections."""
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
core = Path(sys.argv[1])
target = core / "dns/ui_query_count.go"
if target.exists():
    sys.exit("patch_dns_count: overlay already exists; use a clean matching core checkout")
shutil.copyfile(ROOT / "libbox/dns/ui_query_count.go", target)

path = core / "dns/router.go"
text = path.read_text()
hooks = [
    "func (r *Router) Exchange(ctx context.Context, message *mDNS.Msg, options adapter.DNSQueryOptions) (*mDNS.Msg, error) {\n",
    "func (r *Router) ExchangeAsync(ctx context.Context, message *mDNS.Msg, options adapter.DNSQueryOptions, callback func(response *mDNS.Msg, err error)) {\n",
]
for head in hooks:
    if text.count(head) != 1:
        sys.exit("patch_dns_count: DNS router signature changed: " + head.strip())
    text = text.replace(head, head + "\tuiQueryCount.Add(1)\n")
path.write_text(text)
print("Installed the DNS query counter")
