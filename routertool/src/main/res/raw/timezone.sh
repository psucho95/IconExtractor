uci set system.@system[0].timezone='<+05>-5'
uci commit system
/etc/init.d/system reload
date
