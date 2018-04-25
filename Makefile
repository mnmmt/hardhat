all: xmp.js ./build/libxmp/lib/*.so* ./build/xmp-cli/src/xmp

xmp.js: src/xmp.cljs
	./node_modules/.bin/lumo -K -D andare:0.7.0 build.cljs

build/xmp-cli/.git:
	git clone https://github.com/chr15m/xmp-cli.git build/xmp-cli
	cd build/xmp-cli && git checkout sync-delay-api

build/xmp-cli/configure: build/xmp-cli/.git
	cd build/xmp-cli && ./autogen.sh

build/xmp-cli/Makefile: build/xmp-cli/configure
	cd build/xmp-cli && PKG_CONFIG_PATH=$$PKG_CONFIG_PATH:`pwd`/../libxmp LDFLAGS=-L`pwd`/../libxmp/lib CFLAGS=-I`pwd`/../libxmp/include ./configure

./build/xmp-cli/src/xmp: build/xmp-cli/Makefile
	cd build/xmp-cli/ && make

