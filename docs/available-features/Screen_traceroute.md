---
title: Traceroute
slug: available-features/Screen-traceroute
---

### 🛤 Traceroute
- Enter any hostname or IP address
- Tap **Traceroute** to start — button turns red and becomes **Stop** while running
- Output streams live hop-by-hop in a scrolling monospace terminal card
- Implemented via `ping -c 1 -t <TTL>` probing (no `traceroute` binary required)
- Each hop that responds with ICMP Time Exceeded reveals its IP and round-trip time
- Probes up to 30 hops; stops automatically when the destination replies
- History icon reflects the outcome:
  - ✅ Destination reached (all or most hops replied)
  - 🤷‍♂️ Some hops replied but destination not reached
  - ❌ No hop replied at all
- Last 5 traced hosts kept as clickable history (persisted across app restarts)