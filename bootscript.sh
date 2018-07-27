#!/bin/sh

# Gets run from /opt/bootlocal.sh

# turn on the LED so they know we're loading
# (commented out as this breaks the buttons)
#gpio -x mcp23017:100:0x20 mode 106 out
#gpio -x mcp23017:100:0x20 write 106 0

# make sure mount points exist
mkdir -p /mnt/usb
# get into hardhat folder
cd `dirname $0`
# install the udev scripts
cp udev-usb.rules /etc/udev/rules.d/99-udev-usb.rules 2>/dev/null
/sbin/udevadm control --reload-rules &
# launch main script
n=/home/tc/.nvm/versions/node/*/bin/node
$n xmp.js /mnt/usb modules /mnt/mmcblk0p2
