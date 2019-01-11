/**
    ddwOSCSyncClocks: Simple, minimalistic master-slave clock sync across the network
    Copyright (C) 2019  Henry James Harkins

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
**/

DDWMasterClock : TempoClock {
	classvar pingResp;

	var <>id,
	thread, <>addr, <>latency = 0.2;
	var <>period = 0.2;

	*new { |tempo, beats, seconds, queueSize = 256, id(UniqueID.next)|
		^super.new(tempo, beats, seconds, queueSize).id_(id)
	}

	init { arg tempo, beats, seconds, queueSize;
		super.init(tempo, beats, seconds, queueSize);
		NetAddr.broadcastFlag = true;
		addr = NetAddr("255.255.255.255", 57120);  // probably change port later
		this.makePingResponder.startAliveThread;
		ShutDown.add(this);
	}

	// removes the clock, so, clean up everything
	stop {
		this.stopAliveThread;
		addr.sendMsg('/ddwMasterStopped', id);
		ShutDown.remove(this);
		super.stop;
	}
	doOnShutDown { this.stop }  // clean up and especially notify clients

	// TempoClock compatibility
	setTempoAtBeat { arg newTempo, beats;
		super.setTempoAtBeat(newTempo, beats);
		this.prSendSync;
	}
	setMeterAtBeat { arg newBeatsPerBar, beats;
		super.setMeterAtBeat(newBeatsPerBar, beats);
		addr.sendMsg('/ddwClockMeter', id, newBeatsPerBar, beats);
	}

	prSendSync {
		// 'this.beats' now, but 'latency' timestamps for future
		// slave's responsibility to calculate the later beats value
		// that may be a mistake
		addr.sendBundle(latency, ['/ddwClock', id, this.beats, latency, this.tempo, SystemClock.seconds]);
	}

	startAliveThread {
		if(thread.isNil) {
			thread = Routine {
				loop {
					// randomized sync times, to avoid contention at integer valued beats
					// stealing this idea from Scott Wilson's BeaconClock
					((rrand(0.25, 0.75) + rrand(0.25, 0.75) + rrand(0.25, 0.75) + rrand(0.25, 0.75))
						* this.tempo * period).wait;
					this.prSendSync;
				}
			}.play;
		};
		^thread
	}

	stopAliveThread {
		thread.stop;
		thread = nil;
	}

	makePingResponder {
		if(pingResp.isNil) {
			pingResp = OSCFunc({ |msg, time, addr|  // need sender's address
				addr.sendMsg('/ddwPingReply');
			}, '/ddwPing');
		}
	}

	freePingResponder {
		pingResp.free;
		pingResp = nil;
	}
}

UnstableMasterClock : DDWMasterClock {
	var <>instability = 0.025;
	prSendSync {
		// 'this.beats' now, but 'latency' timestamps for future
		// slave's responsibility to calculate the later beats value
		// that may be a mistake
		addr.sendBundle(latency, ['/ddwClock', id, this.beats, latency, this.tempo,
			SystemClock.seconds + instability.rand2]);
	}
}

DDWSlaveClock : TempoClock {
	var <latency, <netDelay, <diff, <addr, <>id;
	var array, indexArray, <medianSize = 7, <>useMedian = true;
	var clockResp, meterResp, stopResp;
	var recalibrate = false;

	// tempo, beats, seconds retained for compatibility, but they are overwritten
	*new { |tempo, beats, seconds, queueSize = 256, id|
		^super.new(tempo, beats, seconds, queueSize).id_(id).makeResponder(id)
	}

	init { arg tempo, beats, seconds, queueSize;
		super.init(tempo, beats, seconds, queueSize);
		NetAddr.broadcastFlag = true;
		addr = NetAddr("255.255.255.255", 57120);  // probably change port later
		// this.ping/*.makeResponder*/;
	}

	stop {
		stopResp.free;
		clockResp.free;
		meterResp.free;
		stopResp = meterResp = clockResp = nil;
		super.stop;
	}

	tempo_ {
		Error("DDWSlaveClock cannot set tempo directly (id = %)".format(id)).throw;
	}
	beatsPerBar_ {
		Error("DDWSlaveClock cannot set beatsPerBar directly (id = %)".format(id)).throw;
	}
	beats_ {
		Error("DDWSlaveClock cannot set beats directly (id = %)".format(id)).throw;
	}

	makeResponder { |argId|
		// var medianSize = 15;  // should be odd
		var argTemplate, value;
		id = argId;
		argTemplate = if(id.notNil) { [id] } { nil };
		stopResp.free;
		stopResp = OSCFunc({ |msg|
			"Master clock ID % stopped. DDWSlaveClock is now free-running.".format(id).warn;
		}, '/ddwMasterStopped', argTemplate: argTemplate);
		meterResp.free;
		meterResp = OSCFunc({ |msg|
			this.setMeterAtBeat(msg[2], msg[3]);
		}, '/ddwClockMeter', argTemplate: argTemplate);
		clockResp.free;
		case
		// default setup: one master, slave doesn't specify ID
		// first tick message from the master should set the ID
		{ id.isNil } {
			clockResp = OSCFunc({ |msg|
				this.makeResponder(msg[1]);
			}, '/ddwClock');
		}
		{ recalibrate or: { diff.isNil } } {
			array = Array(medianSize);
			indexArray = Array(medianSize);
			clockResp = OSCFunc({ |msg, time, argAddr|
				var i;
				// difference = (sysclock + latency) - (time already includes latency)
				// msg.debug("incoming");
				value = (SystemClock.seconds - msg[5] /*time*/) /*+ msg[3]*/;
				// add item in order, to get a moving median
				// if full, remove the oldest
				if(array.size == medianSize) {
					i = indexArray.minIndex;
					array.removeAt(i);
					indexArray.removeAt(i);
					indexArray = indexArray - 1;
				};
				i = array.detectIndex { |item| item > value };
				if(i.isNil) {
					array = array.add(value);
					indexArray = indexArray.add(array.size);
				} {
					array = array.insert(i, value);
					indexArray = indexArray.insert(i, array.size);
				};
				if(useMedian) {
					diff = array.blendAt((array.size - 1) * 0.5);
				} {
					diff = array.mean;
				};
				[time, SystemClock.seconds, value, diff, array.size].debug("calibrating");
				if(netDelay.notNil) {
					this.prSync(msg, time);
				} {
					addr = argAddr;
					this.ping;
				};
				// if(array.size == medianSize) {
				// 	recalibrate = false;
				// 	this.makeResponder(id);  // clear this phase
				// };
			}, '/ddwClock', argTemplate: argTemplate);
		}
		// all set up, normal clock responses
		{
			clockResp = OSCFunc({ |msg, time|
				this.prSync(msg, time);
				value = (SystemClock.seconds - time) + msg[3];
				// the system clock on the master machine might not run at the same speed
				// if that's the case, eventually the measured time difference will drift
				// too far away from 'diff'. If that happens, we need to recalibrate.
				// But, don't recalibrate too often or the timebase will be unstable.
				// A few ms drift are acceptable, not more than that.
				if((value absdif: diff) > 0.007) {
					recalibrate = true;
					this.makeResponder(id);
				};
			}, '/ddwClock', argTemplate: argTemplate);
		}
	}

	// ['/ddwClock', id, this.beats, latency, this.tempo]
	prSync { |msg, time|
		// diff = (my time - their time);
		// 'time' is their time so it's their time + my time - their time --> my time
		// also, 'time' already includes latency so don't add/subtract it here
		SystemClock.schedAbs(msg[5]/*time*/ + diff - netDelay + msg[3], {
			// but msg[2] 'beats' does *not* account for latency; scale to tempo
			latency = msg[3];
			this.prTempo_(msg[4]).prBeats_(msg[2] + (latency * msg[4]));
		});
	}
	prTempo_ { |newTempo| ^super.tempo = newTempo }
	prBeats_ { |newBeats| ^super.beats = newBeats }

	// need a timeout for this in case it fails
	ping { |n(20)|
		Routine {
			var sum = 0, cond = Condition.new,
			lastSent,
			reply = OSCFunc({
				sum = sum + SystemClock.seconds - lastSent;
				cond.unhang;
			}, '/ddwPingReply');
			n.do { |i|
				lastSent = SystemClock.seconds;
				addr.sendMsg('/ddwPing');
				cond.hang;
				netDelay = sum / (i+1);
			};
			"DDWSlaveClock found network delay of %\n".postf(netDelay);
			reply.free;
		}.play(AppClock)
	}

	medianSize_ { |n = 7|
		var new;
		case
		{ n < medianSize } {
			new = array.size - n;
			array = array.select { |item, i| indexArray[i] > new };
			indexArray = indexArray.select { |item, i| indexArray[i] > new };
		}
		{ n > medianSize } {
			new = Array(n);
			array.do { |item| new.add(item) };
			array = new;
			new = Array(n);
			indexArray.do { |item| new.add(item) };
			indexArray = new;
		};
		medianSize = n;
	}
}
