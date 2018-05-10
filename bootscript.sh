#!/bin/sh

# Gets run from /opt/bootlocal.sh

# make sure mount points exist
mkdir -p /mnt/usb
# get into hardhat folder
cd `dirname $0`
# install the udev scripts
cp udev-usb.rules /etc/udev/rules.d/99-udev-usb.rules 2>/dev/null
/sbin/udevadm control --reload-rules &
# launch main script
n=/home/tc/.nvm/versions/node/*/bin/node
$n xmp.js /mnt/usb modules
