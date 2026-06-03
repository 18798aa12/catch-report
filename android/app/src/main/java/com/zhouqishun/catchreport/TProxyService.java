package com.zhouqishun.catchreport;

import android.net.VpnService;

final class TProxyService {
    static {
        System.loadLibrary("hev-socks5-tunnel");
    }

    private TProxyService() {
    }

    static native void TProxyStartService(VpnService vpnService, String configPath, int fd);

    static native void TProxyStopService();

    static native long[] TProxyGetStats();
}
