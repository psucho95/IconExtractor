W=$(uci -q get network.wan.ifname)
i=0
while [ $i -lt 1800 ]; do
  if [ "$W" = "eth1" ]; then s="eth1 carrier=$(cat /sys/class/net/eth1/carrier)"
  else s=$(swconfig dev switch1 show | grep "port:1 " | sed 's/.*port:1 //'); fi
  echo "$(date '+%T') $s | ppp: $(ifconfig pppoe-wan >/dev/null 2>&1 && echo up || echo down)"
  i=$((i + 1))
  sleep 2
done
