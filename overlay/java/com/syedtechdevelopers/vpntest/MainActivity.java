package com.syedtechdevelopers.vpntest;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import de.blinkt.openvpn.api.ExternalAppDatabase;
import de.blinkt.openvpn.api.IOpenVPNAPIService;
import de.blinkt.openvpn.api.IOpenVPNStatusCallback;

/**
 * Chhota test VPN app.
 * - VPN Gate (vpngate.net) se free servers ki list leta hai
 * - OpenVPN engine isi app ke andar hai (koi doosri app nahi chahiye).
 */
public class MainActivity extends Activity {

    private static final String VPNGATE_URL = "http://www.vpngate.net/api/iphone/";
    private static final int REQ_API_PERMISSION = 101;
    private static final int REQ_VPN_PERMISSION = 102;

    private static final int BG = Color.parseColor("#0F1220");
    private static final int CARD = Color.parseColor("#1A1F35");
    private static final int GREEN = Color.parseColor("#22C55E");
    private static final int RED = Color.parseColor("#EF4444");
    private static final int BLUE = Color.parseColor("#3B82F6");
    private static final int AMBER = Color.parseColor("#F59E0B");

    static class Server {
        String host, ip, country, countryShort, proto, config;
        long score, speed;
        int ping;
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<Server> servers = new ArrayList<>();
    private final List<String> rows = new ArrayList<>();
    private ArrayAdapter<String> adapter;

    private IOpenVPNAPIService service;
    private Server selected;
    private boolean connected = false;
    private boolean busy = false;
    private boolean switching = false;      // auto-switch ke dauran NOPROCESS ignore karo
    private boolean callbackRegistered = false;
    private int attempts = 0;
    private static final int MAX_ATTEMPTS = 5;
    private static final long TIMEOUT_MS = 25000;
    private final List<String> logLines = new ArrayList<>();
    private TextView logText;

    private final Runnable timeoutCheck = new Runnable() {
        @Override public void run() {
            if (busy && !connected) {
                log("TIMEOUT", "25s mein connect nahi hua");
                tryNextServer();
            }
        }
    };

    private TextView statusText, serverText, infoText;
    private Button connectBtn;

    // ---------------------------------------------------------------- UI

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(16), dp(20), dp(16), dp(8));

        TextView title = new TextView(this);
        title.setText("VPN Test");
        title.setTextColor(Color.WHITE);
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Free VPN Gate servers • sirf testing ke liye");
        sub.setTextColor(Color.parseColor("#8A93B2"));
        sub.setTextSize(13);
        root.addView(sub);

        statusText = new TextView(this);
        statusText.setTextSize(18);
        statusText.setTypeface(Typeface.DEFAULT_BOLD);
        statusText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.topMargin = dp(18);
        root.addView(statusText, sp);

        connectBtn = new Button(this);
        connectBtn.setTextColor(Color.WHITE);
        connectBtn.setTextSize(20);
        connectBtn.setTypeface(Typeface.DEFAULT_BOLD);
        connectBtn.setAllCaps(false);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(dp(170), dp(170));
        bp.gravity = Gravity.CENTER_HORIZONTAL;
        bp.topMargin = dp(14);
        bp.bottomMargin = dp(14);
        root.addView(connectBtn, bp);
        connectBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onConnectClicked(); }
        });

        serverText = new TextView(this);
        serverText.setTextColor(Color.WHITE);
        serverText.setTextSize(15);
        serverText.setGravity(Gravity.CENTER);
        root.addView(serverText);

        infoText = new TextView(this);
        infoText.setTextColor(Color.parseColor("#8A93B2"));
        infoText.setTextSize(12);
        infoText.setGravity(Gravity.CENTER);
        infoText.setPadding(0, dp(4), 0, dp(6));
        root.addView(infoText);

        logText = new TextView(this);
        logText.setTextColor(Color.parseColor("#9BE7A8"));
        logText.setTextSize(10.5f);
        logText.setTypeface(Typeface.MONOSPACE);
        logText.setBackground(round(Color.parseColor("#090B14"), dp(8)));
        logText.setPadding(dp(8), dp(6), dp(8), dp(6));
        logText.setText("log: abhi khali");
        LinearLayout.LayoutParams lgp = new LinearLayout.LayoutParams(-1, -2);
        lgp.bottomMargin = dp(8);
        root.addView(logText, lgp);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView listTitle = new TextView(this);
        listTitle.setText("Servers (tap karke select karein)");
        listTitle.setTextColor(Color.WHITE);
        listTitle.setTextSize(14);
        listTitle.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(listTitle, new LinearLayout.LayoutParams(0, -2, 1));
        Button refresh = new Button(this);
        refresh.setText("↻ Refresh");
        refresh.setAllCaps(false);
        refresh.setTextColor(Color.WHITE);
        refresh.setBackground(round(CARD, dp(18)));
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { loadServers(); }
        });
        header.addView(refresh, new LinearLayout.LayoutParams(-2, dp(38)));
        root.addView(header);

        ListView list = new ListView(this);
        list.setDividerHeight(dp(6));
        list.setDivider(round(BG, 0));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, 1);
        lp.topMargin = dp(8);
        root.addView(list, lp);

        adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, rows) {
            @Override
            public View getView(int pos, View convert, ViewGroup parent) {
                TextView t = (TextView) super.getView(pos, convert, parent);
                t.setTextColor(Color.WHITE);
                t.setTextSize(14);
                t.setPadding(dp(14), dp(10), dp(14), dp(10));
                boolean sel = pos < servers.size() && servers.get(pos) == selected;
                t.setBackground(round(sel ? Color.parseColor("#24345E") : CARD, dp(12)));
                return t;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                if (connected || busy) {
                    toast("Pehle Disconnect karein, phir server badlein");
                    return;
                }
                selected = servers.get(pos);
                adapter.notifyDataSetChanged();
                showSelected();
            }
        });

        setContentView(root);

        // Hamari apni app ko andar wale OpenVPN engine ki ijazat pehle se de do
        try { new ExternalAppDatabase(this).addApp(getPackageName()); } catch (Exception ignored) { }
        // Android 13+: "Connected" notification dikhane ki ijazat
        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 7);
        }

        setState("Disconnected", false);
        bindOpenVpn();
        loadServers();
    }

    private void setState(String text, boolean isConnected) {
        connected = isConnected;
        statusText.setText(text);
        if (isConnected) {
            statusText.setTextColor(GREEN);
            connectBtn.setText("Disconnect");
            connectBtn.setBackground(oval(RED));
        } else if (busy) {
            statusText.setTextColor(AMBER);
            connectBtn.setText("Connecting…\n(tap = cancel)");
            connectBtn.setTextSize(15);
            connectBtn.setBackground(oval(AMBER));
            return;
        } else {
            statusText.setTextColor(Color.parseColor("#8A93B2"));
            connectBtn.setText("Connect");
            connectBtn.setBackground(oval(BLUE));
        }
        connectBtn.setTextSize(20);
    }

    private void showSelected() {
        if (selected == null) {
            serverText.setText("Koi server select nahi");
            infoText.setText("");
            return;
        }
        serverText.setText(flag(selected.countryShort) + "  " + selected.country);
        infoText.setText(selected.ip + "  •  " + selected.proto.toUpperCase()
                + "  •  ping " + (selected.ping > 0 ? selected.ping + " ms" : "?")
                + "  •  " + mbps(selected.speed));
    }

    // ---------------------------------------------------------- servers

    private void loadServers() {
        infoText.setText("Server list load ho rahi hai…");
        new Thread(new Runnable() {
            @Override public void run() {
                final List<Server> result = new ArrayList<>();
                String error = null;
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(VPNGATE_URL).openConnection();
                    c.setConnectTimeout(15000);
                    c.setReadTimeout(30000);
                    BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (line.isEmpty() || line.startsWith("*") || line.startsWith("#")) continue;
                        String[] f = line.split(",");
                        if (f.length < 15) continue;
                        String b64 = f[f.length - 1];
                        if (b64.length() < 100) continue;
                        Server s = new Server();
                        s.host = f[0];
                        s.ip = f[1];
                        s.score = parseLong(f[2]);
                        s.ping = (int) parseLong(f[3]);
                        s.speed = parseLong(f[4]);
                        s.country = f[5];
                        s.countryShort = f[6];
                        try {
                            s.config = new String(Base64.decode(b64, Base64.DEFAULT), "UTF-8");
                        } catch (Exception e) { continue; }
                        s.proto = s.config.contains("\nproto tcp") ? "tcp" : "udp";
                        result.add(s);
                    }
                    r.close();
                    Collections.sort(result, new Comparator<Server>() {
                        @Override public int compare(Server a, Server b) {
                            // TCP pehle (Pakistan mein UDP aksar block hota hai), phir score
                            int ta = "tcp".equals(a.proto) ? 0 : 1, tb = "tcp".equals(b.proto) ? 0 : 1;
                            if (ta != tb) return ta - tb;
                            return Long.compare(b.score, a.score);
                        }
                    });
                    while (result.size() > 150) result.remove(result.size() - 1);
                    if (result.isEmpty()) error = "Koi server nahi mila";
                } catch (Exception e) {
                    error = "List load nahi hui: " + e.getMessage();
                }
                final String err = error;
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (err != null) {
                            infoText.setText(err + "\n(internet check karein, phir Refresh)");
                            return;
                        }
                        servers.clear();
                        servers.addAll(result);
                        rows.clear();
                        for (Server s : servers) {
                            rows.add(flag(s.countryShort) + "  " + s.country
                                    + "\n" + (s.ping > 0 ? s.ping + " ms" : "? ms")
                                    + "  •  " + mbps(s.speed)
                                    + "  •  " + s.proto.toUpperCase());
                        }
                        if (selected == null && !servers.isEmpty()) selected = servers.get(0);
                        adapter.notifyDataSetChanged();
                        showSelected();
                    }
                });
            }
        }).start();
    }

    /** VPN Gate configs purane cipher use karte hain; naye OpenVPN ke liye allow karna parta hai. */
    private String fixConfig(String cfg) {
        StringBuilder sb = new StringBuilder();
        for (String l : cfg.split("\n")) {
            String t = l.trim();
            if (t.startsWith("#") || t.startsWith(";") || t.isEmpty()) continue;
            sb.append(t).append('\n');
        }
        sb.append("data-ciphers AES-128-CBC:AES-256-GCM:AES-128-GCM:CHACHA20-POLY1305\n");
        sb.append("data-ciphers-fallback AES-128-CBC\n");
        return sb.toString();
    }

    // -------------------------------------------------------- OpenVPN API

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = IOpenVPNAPIService.Stub.asInterface(binder);
            try {
                // Agar ye app pehle allow ho chuki hai to status updates foran milne lagenge
                if (service.prepare(getPackageName()) == null) {
                    service.registerStatusCallback(callback);
                    callbackRegistered = true;
                }
            } catch (Exception ignored) { }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
        }
    };

    private final IOpenVPNStatusCallback.Stub callback = new IOpenVPNStatusCallback.Stub() {
        @Override
        public void newStatus(String uuid, final String state, final String message, String level) {
            ui.post(new Runnable() {
                @Override public void run() { onVpnStatus(state, message); }
            });
        }
    };

    private void log(String state, String message) {
        String line = state + (message != null && !message.isEmpty() ? ": " + message : "");
        if (line.length() > 90) line = line.substring(0, 90) + "…";
        if (!logLines.isEmpty() && logLines.get(logLines.size() - 1).equals(line)) return;
        logLines.add(line);
        while (logLines.size() > 7) logLines.remove(0);
        StringBuilder sb = new StringBuilder();
        for (String l : logLines) { if (sb.length() > 0) sb.append('\n'); sb.append(l); }
        logText.setText(sb.toString());
    }

    private void tryNextServer() {
        ui.removeCallbacks(timeoutCheck);
        attempts++;
        if (attempts >= MAX_ATTEMPTS || servers.isEmpty()) {
            switching = false;
            busy = false;
            try { if (service != null) service.disconnect(); } catch (Exception ignored) { }
            setState("❌ " + attempts + " servers try kiye, connect nahi hua", false);
            return;
        }
        int idx = servers.indexOf(selected);
        selected = servers.get((idx + 1) % servers.size());
        adapter.notifyDataSetChanged();
        showSelected();
        log("SWITCH", "agla server: " + selected.country + " " + selected.ip);
        switching = true;
        try {
            if (service != null) service.disconnect();
        } catch (Exception ignored) { }
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                try { startVpn(); } catch (Exception e) { log("ERROR", e.getMessage()); }
            }
        }, 1500);
    }

    private void onVpnStatus(String state, String message) {
        log(state, message);
        if ("CONNECTED".equals(state)) {
            ui.removeCallbacks(timeoutCheck);
            busy = false;
            switching = false;
            attempts = 0;
            setState("✅ Connected", true);
        } else if ("NOPROCESS".equals(state) || "EXITING".equals(state)) {
            if (switching) return;
            ui.removeCallbacks(timeoutCheck);
            busy = false;
            setState("Disconnected", false);
        } else if ("NONETWORK".equals(state)) {
            setState("Internet nahi hai", false);
        } else if ("USER_VPN_PASSWORD_CANCELLED".equals(state) || "USER_VPN_PERMISSION_CANCELLED".equals(state)) {
            busy = false;
            setState("Permission cancel ho gayi", false);
        } else {
            busy = true;
            connected = false;
            setState(prettyState(state), false);
        }
    }

    private String prettyState(String s) {
        if (s == null) return "Connecting…";
        switch (s) {
            case "WAIT": return "Server ka intezar…";
            case "AUTH": return "Authenticating…";
            case "GET_CONFIG": return "Config le raha hai…";
            case "ASSIGN_IP": return "IP mil rahi hai…";
            case "ADD_ROUTES": return "Routes set ho rahe hain…";
            case "RECONNECTING": return "Dobara connect…";
            case "CONNECTRETRY": return "Dobara koshish…";
            case "VPN_GENERATE_CONFIG": return "Tayyari…";
            default: return "Connecting… (" + s + ")";
        }
    }

    private void bindOpenVpn() {
        Intent i = new Intent(IOpenVPNAPIService.class.getName());
        i.setPackage(getPackageName());
        try {
            bindService(i, conn, Context.BIND_AUTO_CREATE);
        } catch (Exception ignored) { }
    }

    private void onConnectClicked() {
        if (service == null) {
            bindOpenVpn();
            toast("VPN engine start ho raha hai, 2 second baad dobara try karein");
            return;
        }
        try {
            if (connected || busy) {
                ui.removeCallbacks(timeoutCheck);
                switching = false;
                attempts = 0;
                service.disconnect();
                busy = false;
                setState("Disconnected", false);
                return;
            }
            if (selected == null) {
                toast("Pehle server list load hone dein");
                return;
            }
            attempts = 0;
            logLines.clear();
            // Step 1: andar wale engine ki ijazat (aam tor par pehle se mili hoti hai)
            Intent p = service.prepare(getPackageName());
            if (p != null) {
                startActivityForResult(p, REQ_API_PERMISSION);
            } else {
                askVpnPermission();
            }
        } catch (RemoteException e) {
            toast("Error: " + e.getMessage());
        }
    }

    // Step 2: Android ka "VPN connection request" popup
    private void askVpnPermission() throws RemoteException {
        Intent v = service.prepareVPNService();
        if (v != null) {
            startActivityForResult(v, REQ_VPN_PERMISSION);
        } else {
            startVpn();
        }
    }

    // Step 3: tunnel start
    private void startVpn() throws RemoteException {
        if (!callbackRegistered) {
            service.registerStatusCallback(callback);
            callbackRegistered = true;
        }
        busy = true;
        switching = false;
        setState("Connecting… (" + (attempts + 1) + "/" + MAX_ATTEMPTS + ")", false);
        log("START", selected.country + " " + selected.ip + " " + selected.proto.toUpperCase());
        service.startVPN(fixConfig(selected.config));
        ui.removeCallbacks(timeoutCheck);
        ui.postDelayed(timeoutCheck, TIMEOUT_MS);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK) {
            toast("Permission nahi di gayi");
            return;
        }
        try {
            if (requestCode == REQ_API_PERMISSION) askVpnPermission();
            else if (requestCode == REQ_VPN_PERMISSION) startVpn();
        } catch (RemoteException e) {
            toast("Error: " + e.getMessage());
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (service == null) bindOpenVpn();
    }

    @Override
    protected void onDestroy() {
        if (service != null) {
            try { service.unregisterStatusCallback(callback); } catch (Exception ignored) { }
            try { unbindService(conn); } catch (Exception ignored) { }
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------ helpers

    private static long parseLong(String s) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return 0; }
    }

    private static String mbps(long bps) {
        return String.format(java.util.Locale.US, "%.1f Mbps", bps / 1_000_000.0);
    }

    private static String flag(String cc) {
        if (cc == null || cc.length() != 2) return "🌐";
        cc = cc.toUpperCase();
        int a = 0x1F1E6 + (cc.charAt(0) - 'A');
        int b = 0x1F1E6 + (cc.charAt(1) - 'A');
        return new String(Character.toChars(a)) + new String(Character.toChars(b));
    }

    private GradientDrawable oval(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        g.setStroke(dp(6), Color.argb(70, 255, 255, 255));
        return g;
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
