these assets is decompiled from Nekogram and part of it is modified by NekoInverter.
but still,origin author: bbottema https://github.com/bbottema/java-socks-proxy-server

Telewatch also supports the ToiCF/CF-Workers-TGProxy Worker protocol:
https://github.com/ToiCF/CF-Workers-TGProxy/tree/e565c57ff58656fd544755f67e1c266148c0b88f

Deploy the upstream GPL-3.0 telegram_webproxy.js separately to Cloudflare Workers.
Set SECRET to a random 16-byte hex string and BOOT to an independent random
string in the Worker's encrypted variables. In Telewatch's proxy screen, enter
the Worker's HTTPS origin and SECRET. BOOT stays on Cloudflare.

TDLib connects to the app's loopback SOCKS5 listener. The relay opens an HTTPS
session, uses the upstream tproxy-lane-v1 WebSocket protocol, and adds the
Worker secret to TDLib's obfuscation keys. The upstream Worker supports only
production Telegram DCs 1-5, including their media endpoints.

java-socks-proxy-server
License: Apache License 2.0
