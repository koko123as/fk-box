package io.fkbox.kernelprobe;

import android.content.Intent;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Opens the TUN with VpnService.Builder (the app itself excluded from the VPN,
 * the protect replacement in docs/kernel-isolation.md), starts libfdexec.so
 * and hands it the TUN descriptor over a LocalSocket with SCM_RIGHTS;
 * libfdexec.so runs the official kernel with it as fd 3.
 */
public class ProbeVpnService extends VpnService {
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        final String kernel = intent.getStringExtra("kernel");
        final String stack = intent.getStringExtra("stack");
        new Thread(() -> probe(kernel, stack == null ? "gvisor" : stack)).start();
        return START_NOT_STICKY;
    }

    private void probe(String kernel, String stack) {
        try {
            boolean mihomo = "mihomo".equals(kernel);
            Builder builder = new Builder().setSession("kernel-probe").setMtu(1500);
            // mihomo derives its TUN address from dns.fake-ip-range (198.18.0.1/16 -> 198.18.0.1/30).
            builder.addAddress(mihomo ? "198.18.0.1" : "172.19.0.1", 30);
            builder.addRoute("10.233.0.0", 16);
            builder.addDisallowedApplication(getPackageName());
            ParcelFileDescriptor tun = builder.establish();
            if (tun == null) {
                Log.i(MainActivity.TAG, "PROBE establish returned null");
                return;
            }
            String dir = getApplicationInfo().nativeLibraryDir;
            File work = new File(getFilesDir(), kernel);
            work.mkdirs();
            File config = new File(work, mihomo ? "config.yaml" : "config.json");
            copyAsset(mihomo ? "mihomo-" + stack + ".yaml" : "xray.json", config);
            String socket = new File(getFilesDir(), "fdexec.sock").getAbsolutePath();
            List<String> command = new ArrayList<>();
            command.add(dir + "/libfdexec.so");
            command.add("-socket");
            command.add(socket);
            command.add("-seconds");
            command.add("25");
            if (!mihomo) {
                command.add("-env");
                command.add("XRAY_TUN_FD=3");
            }
            command.add("--");
            if (mihomo) {
                command.add(dir + "/libmihomo.so");
                command.add("-d");
                command.add(work.getAbsolutePath());
                command.add("-f");
                command.add(config.getAbsolutePath());
            } else {
                command.add(dir + "/libxray.so");
                command.add("run");
                command.add("-c");
                command.add(config.getAbsolutePath());
            }
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            boolean sent = false;
            while ((line = reader.readLine()) != null) {
                Log.i(MainActivity.TAG, line);
                if (!sent && line.startsWith("FDEXEC listening")) {
                    sent = true;
                    LocalSocket client = new LocalSocket();
                    client.connect(new LocalSocketAddress(socket, LocalSocketAddress.Namespace.FILESYSTEM));
                    client.setFileDescriptorsForSend(new FileDescriptor[]{tun.getFileDescriptor()});
                    OutputStream out = client.getOutputStream();
                    out.write(1);
                    out.flush();
                    client.close();
                    tun.close(); // the kernel keeps its own copy
                    Log.i(MainActivity.TAG, "PROBE descriptor sent");
                }
            }
            Log.i(MainActivity.TAG, "PROBE fdexec exit=" + process.waitFor());
        } catch (Exception e) {
            Log.i(MainActivity.TAG, "PROBE failed: " + e);
        } finally {
            stopSelf();
        }
    }

    private void copyAsset(String name, File target) throws Exception {
        try (InputStream in = getAssets().open(name); FileOutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        }
    }
}
