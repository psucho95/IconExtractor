uci set network.wan.persist='1'
uci set network.wan.maxfail='0'
uci set network.wan.pppd_options='logfile /tmp/ppp.log'
uci commit network
echo "persist: $(uci get network.wan.persist), maxfail: $(uci get network.wan.maxfail), log: $(uci get network.wan.pppd_options)"
echo "Applied on next PPPoE (re)connect."
