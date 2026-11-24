package org.tcp2ws;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;

// Telegram DC mappings from Niagram libs/tcp2ws (see NOTICE.txt).
final class DcMap {
    private final Map<String, String> cdn = new HashMap<>();
    private final Map<Integer, String> mtpcdn = new HashMap<>();

    DcMap(String domain) {
        mtpcdn.put(1, "pluto." + domain);
        mtpcdn.put(2, "venus." + domain);
        mtpcdn.put(3, "aurora." + domain);
        mtpcdn.put(4, "vesta." + domain);
        mtpcdn.put(5, "flora." + domain);
        mtpcdn.put(17, "test_pluto." + domain);
        mtpcdn.put(18, "test_venus." + domain);
        mtpcdn.put(19, "test_aurora." + domain);

//media
        cdn.put("149.154.175.50", "pluto." + domain);
        cdn.put("149.154.167.51", "venus." + domain);
        cdn.put("95.161.76.100", "venus." + domain);
        cdn.put("149.154.175.100", "aurora." + domain);
        cdn.put("149.154.167.91", "vesta." + domain);
        cdn.put("149.154.171.5", "flora." + domain);

        try {
            cdn.put(InetAddress.getByName("2001:b28:f23d:f001:0000:0000:0000:000a").getHostAddress(), "pluto." + domain);
            cdn.put(InetAddress.getByName("2001:67c:4e8:f002:0000:0000:0000:000a").getHostAddress(), "venus." + domain);
            cdn.put(InetAddress.getByName("2001:b28:f23d:f003:0000:0000:0000:000a").getHostAddress(), "aurora." + domain);
            cdn.put(InetAddress.getByName("2001:67c:4e8:f004:0000:0000:0000:000a").getHostAddress(), "vesta." + domain);
            cdn.put(InetAddress.getByName("2001:b28:f23f:f005:0000:0000:0000:000a").getHostAddress(), "flora." + domain);
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }
//proxy
        cdn.put("149.154.175.5", "pluto." + domain);
        cdn.put("149.154.161.144", "venus." + domain);
        cdn.put("149.154.167.15", "venus." + domain);
        cdn.put("149.154.167.5", "venus." + domain);
        cdn.put("149.154.167.6", "venus." + domain);
        cdn.put("149.154.167.7", "venus." + domain);
        cdn.put("149.154.167.2", "venus." + domain);
        cdn.put("91.108.4.", "vesta." + domain);
        cdn.put("149.154.164.", "vesta." + domain);
        cdn.put("149.154.165.", "vesta." + domain);
        cdn.put("149.154.166.", "vesta." + domain);
        cdn.put("149.154.167.8", "vesta." + domain);
        cdn.put("149.154.167.9", "vesta." + domain);
        cdn.put("91.108.56.", "flora." + domain);
        cdn.put("111.62.91.", "venus." + domain);

        // Official Telegram DC IP prefixes (AS62041 / AS44907)
        // DC1 - Miami (pluto)
        cdn.put("149.154.175.", "pluto." + domain);
        cdn.put("149.154.160.", "pluto." + domain);
        cdn.put("149.154.161.", "pluto." + domain);
        cdn.put("149.154.162.", "pluto." + domain);
        cdn.put("149.154.163.", "pluto." + domain);

        // DC2 - Amsterdam (venus)
        cdn.put("149.154.167.", "venus." + domain);
        cdn.put("149.154.168.", "venus." + domain);
        cdn.put("149.154.169.", "venus." + domain);
        cdn.put("149.154.170.", "venus." + domain);
        cdn.put("149.154.171.", "venus." + domain);
        cdn.put("91.108.8.", "venus." + domain);
        cdn.put("91.108.9.", "venus." + domain);
        cdn.put("91.108.10.", "venus." + domain);
        cdn.put("91.108.11.", "venus." + domain);
        cdn.put("91.108.16.", "venus." + domain);
        cdn.put("91.108.17.", "venus." + domain);
        cdn.put("91.108.18.", "venus." + domain);
        cdn.put("91.108.19.", "venus." + domain);

        // DC3 - Miami (aurora)
        cdn.put("149.154.172.", "aurora." + domain);
        cdn.put("149.154.173.", "aurora." + domain);
        cdn.put("149.154.174.", "aurora." + domain);

        // DC4 - Amsterdam (vesta)
        cdn.put("91.108.5.", "vesta." + domain);
        cdn.put("91.108.6.", "vesta." + domain);
        cdn.put("91.108.7.", "vesta." + domain);
        cdn.put("91.108.12.", "vesta." + domain);
        cdn.put("91.108.13.", "vesta." + domain);
        cdn.put("91.108.14.", "vesta." + domain);
        cdn.put("91.108.15.", "vesta." + domain);

        // DC5 - Singapore (flora) - Key data center for Asian users
        cdn.put("91.108.20.", "flora." + domain);
        cdn.put("91.108.21.", "flora." + domain);
        cdn.put("91.108.22.", "flora." + domain);
        cdn.put("91.108.23.", "flora." + domain);
        cdn.put("91.108.57.", "flora." + domain);
        cdn.put("91.108.58.", "flora." + domain);
        cdn.put("91.108.59.", "flora." + domain);

        try {
            cdn.put(InetAddress.getByName("2001:b28:f23d:f001:0000:0000:0000:000d").getHostAddress(), "pluto." + domain);
            cdn.put(InetAddress.getByName("2001:67c:4e8:f002:0000:0000:0000:000d").getHostAddress(), "venus." + domain);
            cdn.put(InetAddress.getByName("2001:b28:f23d:f003:0000:0000:0000:000d").getHostAddress(), "aurora." + domain);
            cdn.put(InetAddress.getByName("2001:67c:4e8:f004:0000:0000:0000:000d").getHostAddress(), "vesta." + domain);
            cdn.put(InetAddress.getByName("2001:b28:f23f:f005:0000:0000:0000:000d").getHostAddress(), "flora." + domain);
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }

//test
        cdn.put("149.154.175.10", "test_pluto." + domain);
        cdn.put("149.154.175.40", "test_pluto." + domain);
        cdn.put("149.154.167.40", "test_venus." + domain);
        cdn.put("149.154.175.117", "test_aurora." + domain);

        try {
            cdn.put(InetAddress.getByName("2001:b28:f23d:f001:0000:0000:0000:000e").getHostAddress(), "test_pluto." + domain);
            cdn.put(InetAddress.getByName("2001:67c:4e8:f002:0000:0000:0000:000e").getHostAddress(), "test_venus." + domain);
            cdn.put(InetAddress.getByName("2001:b28:f23d:f003:0000:0000:0000:000e").getHostAddress(), "test_aurora." + domain);
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }

    }

    String lookup(InetAddress address) {
        String host = address.getHostAddress();
        String exact = cdn.get(host);
        if (exact != null) return exact;
        int dot = host.lastIndexOf('.');
        return dot < 0 ? null : cdn.get(host.substring(0, dot + 1));
    }
}
