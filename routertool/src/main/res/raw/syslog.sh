grep -iE "wan_check|netifd|pppd|link" /tmp/messages 2>/dev/null | grep -v luci | grep -viE "wl[0-9]|miot" | tail -80 | mask
