mask() { sed -r 's/(\["(password|username|pppoename)"\] = ")[^"]*/\1***/g; s/(user|password) [^ ]+/\1 ***/g'; }
