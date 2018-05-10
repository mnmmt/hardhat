#!/usr/bin/env node

pty = require('node-pty');
cp = require('child_process');
try {
  gpio = require('onoff').Gpio;
} catch(e) {
  gpio = null;
}

var re_tick = /\(tick (.*?)\)/g;

if (gpio) {
  var syncpin = new gpio(17, "out");

  function syncping() {
    syncpin.writeSync(1);
    setTimeout(function() {
      syncpin.writeSync(0);
    }, 4);
  }
} else {
  function syncping() {
    console.log("sync");
  }
}

function ms() {
  //var t = process.hrtime();
  //return (t[0] * 1000 + t[1] / 1000000);
  return (new Date()).getTime();
}

/*var scheduled = [];
//var lastpop = 0;
var hrTime = process.hrtime()
var start = hrTime[0] * 1000 + hrTime[1] / 1000000;
setInterval(function() {
  var hrTime = process.hrtime()
  var now = (hrTime[0] * 1000 + hrTime[1] / 1000000 - start);
  if (now > scheduled[0]) {
    syncping();
    console.log("pop", scheduled[0], now);
    scheduled.shift(0);
    //lastpop = now;
  }
}, 1);*/

var last = Number.MAX_VALUE;
var ticker = null;
var last_info = null;
var lastrow = -1;
var lasttime = 0;
var sampleclock = 0;
function handledata(data) {
  var now = ms();
  var lines = data.toString().split("\r\n");
  for (var l=0; l<lines.length; l++) {
    if (lines[l].length > 2) {
      try {
        var s = JSON.parse(lines[l]);
      } catch(e) {
        var s = {};
      }
      if (s) {
        /*
        last_info = s;
        if (s.time < last && !ticker) {
          clearInterval(ticker);
          syncping();
          var len = Math.round(60000.0 / s.bpm);
          setTimeout(function() {
            ticker = setInterval(function() {
              //console.log("last", last_info);
              syncping();
            }, len);
          }, len - 50);
          console.log("sync rate:", len);
        }
        //console.log(s);
        console.log();
        last = s.time;
        */
        //console.log(lines[l]);
        //console.log(s.abstime);
        //sampleclock += s.time - lasttime;
        //lasttime = s.time;
        if (s.row % 4 == 0 && s.row != lastrow) {
          var nextTick = s.time_alsa_delay - (ms() - s.time_hw);
          setTimeout(syncping, Math.max(0, nextTick));
          console.log("tick", s.row, now, s.time_hw, s.time_alsa, s.time_alsa_delay, nextTick);
          console.log(nextTick);
          //console.log("push", s.abstime, s.row);
          //scheduled.push(s.abstime);
          lastrow = s.row;
        }
      }
    }
  }
}

/*var start = (new Date()).getTime();
var last_tick = -1;
var last_time = 0;
function handledata(data) {
  var now = (new Date()).getTime();
  console.log(" --> ", now - start);
  var lines = data.toString().split("\r\n");
  //console.log(lines);
  for (var l=0; l<lines.length; l++) {
    var str = lines[l];
    if (str.length > 2) {
      console.log(str);
      var s = JSON.parse(str);
      
      if (s.time < last_time) {
        last_tick = -1;
      }
      last_time = s.time;
      
      var calcframe = s.time / (60000.0 / s.bpm / 8.0);
      console.log(s.time, s.row, calcframe, Math.round(calcframe) / 8.0);
      while (Math.round(calcframe) > last_tick * 4.0) {
        console.log("syncping");
        // TODO: should queue these up with 3ms delay
        syncping();
        last_tick += 1;
      }
    }
  }
  console.log();
}*/

// xmp = pty.spawn("./xmp-wrap", ["-l", "./test.it"]);
//xmp = cp.spawn("stdbuf", ["-i0", "-o0", "-e0", "./xmp-wrap", "-l", "./modules/xerxes-mfc.xm"]);
//xmp = cp.spawn("stdbuf", ["-i0", "-o0", "-e0", "./xmp-wrap", "-d", "alsa", "-D", "buffer=25", "-D", "period=25", "-l", "./test.it"]);

// setting buffer size can reduce the per-tick-print interval to 15ms which means less sync jitter

//xmp = cp.spawn("./xmp-wrap", ["-d", "alsa", "-D", "buffer=25", "-D", "period=25", "-l", "./test.it"]);
//xmp.stdout.on("data", handledata);

xmp = pty.spawn("./xmp-wrap", ["-l", process.argv.pop()], function() {});
xmp.on("data", handledata);

//setTimeout(function() {
  //process.exit();
//}, 1);

process.stdin.setRawMode(true);
process.stdin.resume();
process.stdin.on('data', process.exit.bind(process, 0));
