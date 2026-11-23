# Rules that need DNS, without DNS in decide

`decide` runs synchronously inside the host's connection setup and must not
block (see [brain-ffi.md](brain-ffi.md)). Some rules need an address the
connection may not have: `IP-CIDR`, `GEOIP`, an ipcidr `RULE-SET`, matched
against a connection whose destination is a domain (an FQDN destination from
a SOCKS/HTTP inbound or a fake-IP mapping). This is how the engine decides
them.

## Which address an IP condition reads

1. A destination address present in the metadata is always used (a TUN
   connection with a real destination; its sniffed domain is matched by the
   domain rules). IPv4-mapped IPv6 is read as IPv4.
2. With no destination address, a condition marked **resolve** (mihomo IP
   rules without `no-resolve`, ipcidr providers without `no-resolve`) reads
   the brain's **DNS cache** (`DnsView::lookup`): cached addresses (at most 8
   per name, unmapped) are matched; a cached empty answer is a definite "no
   address".
3. Conditions marked **no-resolve** (mihomo `no-resolve`, all sing-box IP
   items, since sing-box itself matches them only after a `resolve` action)
   never read the cache and are false for a domain-only connection.
4. Source conditions (`SRC-IP-CIDR`, `SRC-GEOIP`, `src`) read the source
   address only.

The cache is looked up at most once per decision, and only when an IP rule
that reads it could still win (its position is before the best match found
so far).

## A cache miss

Each resolving condition carries a miss policy, set at import
(`ClashOptions::miss`, default `skip`):

| Policy | The condition on a miss | `NOT(IP-CIDR …)` on a miss |
| --- | --- | --- |
| `skip` | **unknown** | unknown: the rule is skipped |
| `no-resolve` | false (mihomo `no-resolve` semantics) | true: the rule matches |

Logic is three-valued (Kleene): `AND` is false if any part is false, else
unknown if any is unknown; `OR` is true if any part is true, else unknown if
any is unknown; `NOT` keeps unknown. A top-level rule that evaluates to
unknown is skipped and evaluation continues with the next rule, so "skip"
means exactly "this rule does not take part for this connection", whatever
logic wraps the IP condition. For a plain top-level IP rule both policies
behave alike (no match); they differ inside `NOT` and `AND`/`OR`.

On a miss the decision also:

- sets `FKB_DECISION_DNS_MISS` in `fkb_decision.flags` (the host can count
  or log it);
- calls `DnsView::prefetch(domain)` once: the asynchronous warm-up hook.
  In stage 1 it queues the name in a bounded, de-duplicated queue
  (`DnsCache::take_prefetch`), dropping the hint rather than waiting on a
  contended lock. Stage 4 drains it with the brain's resolver (queries sent
  through `exchange`) so the next connection to the same name hits the
  cache.

The decision is never delayed for DNS: a connection that arrives before the
cache is warm is routed as if the rule were absent (`skip`) or false
(`no-resolve`), and later connections get the cached answer.

## Not covered here

- How the cache is filled, TTLs beyond what an answer carries, negative
  caching policy, fake-IP: stage 4 (DNS in the brain).
- A subscription's own DNS policy (its `dns:` section, nameserver policies,
  fallback filters): not handled; to be decided by the user.
- Rules on the resolved address of a connection that already has a
  destination address (mihomo never does this; sing-box `resolve` action):
  not supported, the importer refuses `resolve`.
