W=$(uci -q get network.wan.ifname); [ -n "$W" ] || W=eth1
echo "--- uptime / time"
uptime
date
echo "--- WAN"
echo "WAN iface:  $W (proto: $(uci -q get network.wan.proto))"
if [ "$W" = "eth1" ]; then
  echo "phys link:  eth1 carrier=$(cat /sys/class/net/eth1/carrier 2>/dev/null) (2.5G)"
else
  echo "phys link:  $(swconfig dev switch1 show | grep 'port:1 ' | sed 's/.*port:1 //')"
fi
echo "pppd:       $(pidof pppd >/dev/null && echo running || echo NOT running)"
echo "WAN IP:     $(ifconfig pppoe-wan 2>/dev/null | sed -n 's/.*inet addr:\([0-9.]*\).*/\1/p')"
echo "persist:    $(uci -q get network.wan.persist), maxfail: $(uci -q get network.wan.maxfail)"
echo "ppp log:    $(uci -q get network.wan.pppd_options)"
if ping -c 2 -W 3 1.1.1.1 >/dev/null 2>&1 || ping -c 2 -W 3 8.8.8.8 >/dev/null 2>&1; then
  echo "internet:   OK"
else
  echo "internet:   NO"
fi
echo "--- link monitor"
if [ -f /tmp/linkmon.pid ] && kill -0 $(cat /tmp/linkmon.pid) 2>/dev/null; then
  echo "linkmon:    running"
else
  echo "linkmon:    NOT running (install it)"
fi
tail -5 /data/linkmon.log 2>/dev/null
