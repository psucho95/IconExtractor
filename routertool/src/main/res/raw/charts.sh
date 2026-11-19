echo "NOW $(date +%s)"
echo "TZ $(date +%z)"
echo "WAN $(uci -q get network.wan.ifname)"
echo "MSGSTART $(grep -m1 -E '^20(2[5-9]|[3-9][0-9])-' /tmp/messages 2>/dev/null | cut -d' ' -f1)"
sed 's/^/LM /' /data/linkmon.log 2>/dev/null
grep -E "netifd: (VLAN '[^']+'|Network device 'eth[0-9]') link is (up|down)|netifd: Interface 'wan' (is now (up|down)|has lost the connection|has link connectivity loss)|pppd\[[0-9]+\]: LCP terminated by peer" /tmp/messages 2>/dev/null | grep -E '^20(2[5-9]|[3-9][0-9])-' | sed 's/^/EV /'
