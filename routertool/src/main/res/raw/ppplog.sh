if [ -s /tmp/ppp.log ]; then tail -80 /tmp/ppp.log | mask
else grep "pppd\[" /tmp/messages | tail -80 | mask; fi
