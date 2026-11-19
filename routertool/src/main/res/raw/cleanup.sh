[ -x /data/wan_watchdog.sh ] && /data/wan_watchdog.sh stop
uci -q delete firewall.wan_watchdog
uci commit firewall
rm -f /data/wan_watchdog.sh /data/wan_watchdog_reboots.log /tmp/wan_watchdog.log /tmp/wan_watchdog.pid
echo "old watchdog removed"
ls /data
