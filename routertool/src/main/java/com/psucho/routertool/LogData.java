package com.psucho.routertool;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the output of res/raw/charts.sh into down-intervals per lane and computes statistics.
 * All times are epoch seconds (UTC); conversion to the phone's time zone happens only when drawing.
 */
final class LogData {

    static final int LANE_LINK = 0;   // physical WAN link (provider cable)
    static final int LANE_PPP = 1;    // PPPoE session ('wan' interface)
    static final int LANE_PC = 2;     // PC port (2.5G, eth1) when it is LAN

    static final String[] LANE_NAMES = {"Кабель WAN", "PPPoE", "Порт ПК"};

    /** A period when something was down. */
    static final class Down {
        final long start, end;
        final boolean ongoing;
        Down(long start, long end, boolean ongoing) { this.start = start; this.end = end; this.ongoing = ongoing; }
        long dur() { return end - start; }
    }

    /** An incident = merged outage as the user felt it. */
    static final class Incident {
        long start, end;
        boolean physical;    // the cable link dropped (otherwise PPPoE only)
        boolean ongoing;
        int flaps = 1;       // how many separate link drops were merged into it
        long dur() { return end - start; }
    }

    long now;
    ZoneOffset routerOffset = ZoneOffset.UTC;
    String wanIface = "";
    final List<List<Down>> lanes = new ArrayList<>();
    final long[] coverageStart = {Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE};
    final List<Long> providerResets = new ArrayList<>();   // "LCP terminated by peer"
    final List<Incident> incidents = new ArrayList<>();

    private static final Pattern LM = Pattern.compile(
            "^(\\d{4}-\\d{2}-\\d{2}) (\\d{2}:\\d{2}:\\d{2}) (.*?)(?: @(\\d+))?\\s*$");
    private static final Pattern DEV = Pattern.compile(
            "netifd: (?:VLAN|Network device) '([^']+)' link is (up|down)");

    /** Gaps shorter than this between two link drops are treated as one flapping incident. */
    static final long MERGE_GAP = 60;

    static LogData parse(String raw) {
        LogData d = new LogData();
        for (int i = 0; i < 3; i++) d.lanes.add(new ArrayList<>());
        List<long[]> link = new ArrayList<>();     // {time, up?1:0}
        List<long[]> linkMsg = new ArrayList<>();
        List<long[]> ppp = new ArrayList<>();
        List<long[]> pc = new ArrayList<>();
        d.now = System.currentTimeMillis() / 1000;

        String[] lines = raw.split("\n");
        // first pass: header values (offset is needed to parse linkmon lines)
        for (String line : lines) {
            if (line.startsWith("NOW ")) {
                try { d.now = Long.parseLong(line.substring(4).trim()); } catch (Exception ignored) { }
            } else if (line.startsWith("TZ ")) {
                String z = line.substring(3).trim();          // like +0800
                if (z.matches("[+-]\\d{4}")) d.routerOffset = ZoneOffset.of(z.substring(0, 3) + ":" + z.substring(3));
            } else if (line.startsWith("WAN ")) {
                d.wanIface = line.substring(4).trim();
            } else if (line.startsWith("MSGSTART ")) {
                Long t = iso(line.substring(9).trim());
                if (t != null) {
                    d.coverageStart[LANE_PPP] = t;
                    d.coverageStart[LANE_PC] = t;
                }
            }
        }
        String wanDev = d.wanIface.isEmpty() ? "eth0.2" : d.wanIface;

        for (String line : lines) {
            if (line.startsWith("LM ")) {
                Matcher m = LM.matcher(line.substring(3));
                if (!m.matches()) continue;
                String state = m.group(3);
                if (state.contains("linkmon started")) continue;
                long t;
                if (m.group(4) != null) t = Long.parseLong(m.group(4));
                else t = LocalDateTime.parse(m.group(1) + "T" + m.group(2)).toEpochSecond(d.routerOffset);
                boolean up = state.contains("link:up") && !state.contains("link:down");
                link.add(new long[]{t, up ? 1 : 0});
            } else if (line.startsWith("EV ")) {
                String ev = line.substring(3);
                int sp = ev.indexOf(' ');
                if (sp < 0) continue;
                Long t = iso(ev.substring(0, sp));
                if (t == null) continue;
                Matcher m = DEV.matcher(ev);
                if (m.find()) {
                    boolean up = m.group(2).equals("up");
                    String dev = m.group(1);
                    if (dev.equals(wanDev)) linkMsg.add(new long[]{t, up ? 1 : 0});
                    else if (dev.equals("eth1")) pc.add(new long[]{t, up ? 1 : 0});
                } else if (ev.contains("Interface 'wan' is now up")) {
                    ppp.add(new long[]{t, 1});
                } else if (ev.contains("Interface 'wan' is now down") || ev.contains("Interface 'wan' has lost")
                        || ev.contains("Interface 'wan' has link connectivity loss")) {
                    ppp.add(new long[]{t, 0});
                } else if (ev.contains("LCP terminated by peer")) {
                    d.providerResets.add(t);
                }
            }
        }

        // physical link: the persistent linkmon journal is primary; the system log fills in
        // the time before the journal starts (it only covers the time since the router booted)
        Collections.sort(link, (x, y) -> Long.compare(x[0], y[0]));
        long lmStart = link.isEmpty() ? Long.MAX_VALUE : link.get(0)[0];
        for (long[] e : linkMsg) if (e[0] < lmStart) link.add(e);
        Collections.sort(link, (x, y) -> Long.compare(x[0], y[0]));
        long cov = lmStart;
        if (!linkMsg.isEmpty()) cov = Math.min(cov, d.coverageStart[LANE_PPP]);
        if (!link.isEmpty()) cov = Math.min(cov, link.get(0)[0]);
        d.coverageStart[LANE_LINK] = cov;
        if (d.wanIface.equals("eth1")) pc.clear();   // 2.5G port is WAN then, no separate PC lane

        d.lanes.set(LANE_LINK, toDowns(link, d.now));
        d.lanes.set(LANE_PPP, toDowns(ppp, d.now));
        d.lanes.set(LANE_PC, toDowns(pc, d.now));
        d.buildIncidents();
        return d;
    }

    private static Long iso(String s) {
        try { return OffsetDateTime.parse(s).toEpochSecond(); } catch (Exception e) { return null; }
    }

    private static List<Down> toDowns(List<long[]> ev, long now) {
        Collections.sort(ev, (a, b) -> Long.compare(a[0], b[0]));
        List<Down> out = new ArrayList<>();
        long downSince = -1;
        for (long[] e : ev) {
            if (e[1] == 0) {
                if (downSince < 0) downSince = e[0];
            } else if (downSince >= 0) {
                out.add(new Down(downSince, e[0], false));
                downSince = -1;
            }
        }
        if (downSince >= 0) out.add(new Down(downSince, now, true));
        return out;
    }

    /** Merge link flaps and add PPPoE-only outages that are not explained by a link drop. */
    private void buildIncidents() {
        incidents.clear();
        Incident cur = null;
        for (Down dn : lanes.get(LANE_LINK)) {
            if (cur != null && dn.start - cur.end <= MERGE_GAP) {
                cur.end = Math.max(cur.end, dn.end);
                cur.ongoing |= dn.ongoing;
                cur.flaps++;
                continue;
            }
            cur = new Incident();
            cur.start = dn.start; cur.end = dn.end; cur.physical = true; cur.ongoing = dn.ongoing;
            incidents.add(cur);
        }
        List<Incident> phys = new ArrayList<>(incidents);
        for (Down dn : lanes.get(LANE_PPP)) {
            if (dn.dur() < 2) continue;
            boolean explained = false;
            for (Incident p : phys) {
                // PPPoE drop that overlaps a link outage (or follows it by up to 60 s) is part of it
                if (dn.start <= p.end + 60 && dn.end >= p.start - 5) { explained = true; break; }
            }
            if (explained) continue;
            Incident i = new Incident();
            i.start = dn.start; i.end = dn.end; i.physical = false; i.ongoing = dn.ongoing;
            incidents.add(i);
        }
        Collections.sort(incidents, (a, b) -> Long.compare(a.start, b.start));
    }

    // ---------------------------------------------------------------- statistics

    static final class Stats {
        int count, physical, pppOnly;
        long downtime, longest;
        double availability = 1;   // share of the covered time when internet was up
        long covered;
    }

    Stats stats(long from, long to) {
        Stats s = new Stats();
        long cover = Math.max(from, Math.min(coverageStart[LANE_LINK], coverageStart[LANE_PPP]));
        if (cover == Long.MAX_VALUE) cover = from;
        s.covered = Math.max(0, to - cover);
        for (Incident i : incidents) {
            long a = Math.max(i.start, from), b = Math.min(i.end, to);
            if (b <= a && !(i.start >= from && i.start <= to)) continue;
            s.count++;
            if (i.physical) s.physical++; else s.pppOnly++;
            long d = Math.max(0, b - a);
            s.downtime += d;
            s.longest = Math.max(s.longest, i.dur());
        }
        if (s.covered > 0) s.availability = Math.max(0, 1.0 - (double) s.downtime / s.covered);
        return s;
    }

    boolean isEmpty() {
        return lanes.get(LANE_LINK).isEmpty() && lanes.get(LANE_PPP).isEmpty()
                && coverageStart[LANE_LINK] == Long.MAX_VALUE && coverageStart[LANE_PPP] == Long.MAX_VALUE;
    }
}
