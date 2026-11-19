echo "Restarting PPPoE (ifdown/ifup wan)..."
ifdown wan
sleep 3
ifup wan
echo "waiting 15 s..."
sleep 15
ifconfig pppoe-wan 2>/dev/null | grep "inet addr" || echo "pppoe-wan is not up yet"
