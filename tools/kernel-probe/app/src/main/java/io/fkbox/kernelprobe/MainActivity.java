package io.fkbox.kernelprobe;

import android.app.Activity;
import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;

/**
 * Runs each bundled kernel's version command from nativeLibraryDir (exec as
 * the app's own untrusted_app domain), then starts the VPN probe for the
 * kernel named by the "kernel" extra.
 */
public class MainActivity extends Activity {
    static final String TAG = "KPROBE";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String kernel = getIntent().getStringExtra("kernel");
        new Thread(() -> {
            String dir = getApplicationInfo().nativeLibraryDir;
            run(new String[]{dir + "/libxray.so", "version"});
            run(new String[]{dir + "/libmihomo.so", "-v"});
            if (kernel != null) {
                if (VpnService.prepare(this) != null) {
                    Log.i(TAG, "PROBE vpn not prepared (appops ACTIVATE_VPN missing)");
                    return;
                }
                Intent intent = new Intent(this, ProbeVpnService.class);
                intent.putExtra("kernel", kernel);
                intent.putExtra("stack", getIntent().getStringExtra("stack"));
                intent.putExtra("exclude", getIntent().getStringExtra("exclude"));
                startService(intent);
            }
        }).start();
    }

    static void run(String[] command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                Log.i(TAG, "EXEC " + new File(command[0]).getName() + ": " + line);
            }
            Log.i(TAG, "EXEC " + new File(command[0]).getName() + " exit=" + process.waitFor());
        } catch (Exception e) {
            Log.i(TAG, "EXEC " + command[0] + " failed: " + e);
        }
    }
}
