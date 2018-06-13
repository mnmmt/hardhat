### Compiling XMP

https://github.com/cmatsuoka/xmp-cli/issues/9

	$ git clone http://github.com/cmatsuoka/libxmp
	$ git clone http://github.com/cmatsuoka/xmp-cli
	$ cd libxmp
	$ autoconf
	$ ./configure
	$ make
	$ cd ../libxmp-cli
	$ ./autogen.sh
	$ PKG_CONFIG_PATH=$PKG_CONFIG_PATH:/home/claudio/libxmp LDFLAGS=-L/home/claudio/libxmp/lib CFLAGS=-I/home/claudio/libxmp/include ./configure
	$ make
	$ LD_LIBRARY_PATH=/home/claudio/libxmp/lib src/xmp

