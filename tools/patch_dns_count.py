#!/usr/bin/env python3
"""Count the DNS messages sing-box's DNS router answers (core/dns/), so the
dashboard can show DNS queries that the core resolves itself (hijack-dns)
instead of only those that became connections.

    python3 tools/patch_dns_count.py .core            # apply
    python3 tools/patch_dns_count.py .core --check    # only report whether it fits
"""
import shutil
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from patchcheck import check_absent, check_anchor, report  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
LABEL = "patch_dns_count"
ROUTER = "dns/router.go"
HOOKS = [
    "func (r *Router) Exchange(ctx context.Context, message *mDNS.Msg, options adapter.DNSQueryOptions) (*mDNS.Msg, error) {\n",
    "func (r *Router) ExchangeAsync(ctx context.Context, message *mDNS.Msg, options adapter.DNSQueryOptions, callback func(response *mDNS.Msg, err error)) {\n",
]


def main(argv):
    check = "--check" in argv
    arguments = [a for a in argv if a != "--check"]
    if len(arguments) != 1:
        print(__doc__)
        return 2
    core = Path(arguments[0])
    target = core / "dns/ui_query_count.go"
    path = core / ROUTER
    if check:
        failures = check_absent([target], core, LABEL)
        if not path.is_file():
            failures.append(f"{LABEL}: {ROUTER}: file not found in the core")
        else:
            text = path.read_text()
            for head in HOOKS:
                failures += check_anchor(text, head, ROUTER, LABEL)
        return report(failures, LABEL, f"{ROUTER}: Exchange and ExchangeAsync signatures found")
    if target.exists():
        sys.exit("patch_dns_count: overlay already exists; use a clean matching core checkout")
    shutil.copyfile(ROOT / "core/dns/ui_query_count.go", target)
    text = path.read_text()
    for head in HOOKS:
        if text.count(head) != 1:
            sys.exit("patch_dns_count: DNS router signature changed: " + head.strip())
        text = text.replace(head, head + "\tuiQueryCount.Add(1)\n")
    path.write_text(text)
    print("Installed the DNS query counter")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
