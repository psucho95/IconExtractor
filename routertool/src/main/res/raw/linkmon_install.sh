cat > /data/linkmon.sh <<'EOF'
#!/bin/sh
# Logs every change of the physical WAN link to /data/linkmon.log (survives reboot).
# Started at boot via firewall include (the firewall sources this file).
SW_PORT=1
LOG=/data/linkmon.log
PIDF=/tmp/linkmon.pid

lm_state() {
    W=$(uci -q get network.wan.ifname)
    if [ "$W" = "eth1" ]; then
        if [ "$(cat /sys/class/net/eth1/carrier 2>/dev/null)" = "1" ]; then echo "eth1 link:up"; else echo "eth1 link:down"; fi
    else
        swconfig dev switch1 show | grep "port:$SW_PORT " | sed "s/.*port:$SW_PORT //"
    fi
}

lm_run() {
    echo $$ > "$PIDF"
    echo "$(date '+%F %T') linkmon started" >> "$LOG"
    prev=""
    while true; do
        s=$(lm_state)
        if [ "$s" != "$prev" ]; then
            echo "$(date '+%F %T') $s" >> "$LOG"
            prev="$s"
            if [ "$(wc -l < "$LOG")" -gt 2000 ]; then
                tail -1000 "$LOG" > "$LOG.tmp" && mv "$LOG.tmp" "$LOG"
            fi
        fi
        sleep 5
    done
}

case "$1" in
    run) lm_run ;;
    *)
        # restore SSH key for passwordless login after reboot
        if [ -s /data/authorized_keys ]; then
            mkdir -p /etc/dropbear
            cp /data/authorized_keys /etc/dropbear/authorized_keys
            chmod 600 /etc/dropbear/authorized_keys
        fi
        if ! { [ -f "$PIDF" ] && kill -0 "$(cat "$PIDF")" 2>/dev/null; }; then
            sh /data/linkmon.sh run </dev/null >/dev/null 2>&1 &
        fi
        ;;
esac
EOF
chmod +x /data/linkmon.sh
# stop any running copies (including the one started by hand earlier)
for p in $(ps | grep '[l]inkmon.sh' | awk '{print $1}'); do kill $p; done
rm -f /tmp/linkmon.pid
# autostart at boot
uci set firewall.linkmon=include
uci set firewall.linkmon.type='script'
uci set firewall.linkmon.path='/data/linkmon.sh'
uci set firewall.linkmon.enabled='1'
uci commit firewall
sh /data/linkmon.sh
sleep 2
[ -f /tmp/linkmon.pid ] && kill -0 $(cat /tmp/linkmon.pid) 2>/dev/null && echo "linkmon: running, autostart enabled" || echo "linkmon: FAILED to start"
tail -3 /data/linkmon.log
