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
		addr.sendBundle(latency, ['/ddwClock', id, this.beats, latency, this.tempo]);
	}

	startAliveThread {
		if(thread.isNil) {
			thread = Routine {
				loop {
					// randomized sync times, to avoid contention at integer valued beats
					// stealing this idea from Scott Wilson's BeaconClock
					(rrand(0.2, 0.6) * this.tempo).wait;
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

DDWSlaveClock : TempoClock {
	var <latency, <netDelay, <diff, <addr, <>id;
	var clockResp, meterResp, stopResp;

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
	}

	makeResponder { |argId|
		var argTemplate, calibrateCount, sum;
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
		{ diff.isNil } {
			calibrateCount = 0;
			sum = 0;
			clockResp = OSCFunc({ |msg, time, argAddr|
				// difference = (sysclock + latency) - (time already includes latency)
				// but we want to subtract this later, so '.neg' it below
				sum = sum + (SystemClock.seconds - time) + msg[3];
				calibrateCount = calibrateCount + 1;
				diff = sum.neg / calibrateCount;
				[time, SystemClock.seconds, sum, diff, calibrateCount].debug("calibrating");
				if(netDelay.notNil) {
					this.prSync(msg, time);
				} {
					addr = argAddr;
					this.ping;
				};
				if(calibrateCount >= 10) {
					this.makeResponder(id);  // clear this phase
				};
			}, '/ddwClock', argTemplate: argTemplate);
		}
		// all set up, normal clock responses
		{
			clockResp = OSCFunc({ |msg, time|
				this.prSync(msg, time);
			}, '/ddwClock', argTemplate: argTemplate);
		}
	}

	// ['/ddwClock', id, this.beats, latency, this.tempo]
	prSync { |msg, time|
		// diff = (my time - their time);
		// 'time' is their time so it's their time + my time - their time --> my time
		// also, 'time' already includes latency so don't add/subtract it here
		SystemClock.schedAbs(time + diff - netDelay, {
			// but msg[2] 'beats' does *not* account for latency; scale to tempo
			latency = msg[3];
			this.tempo_(msg[4]).beats_(msg[2] + (latency * msg[4]));
		});
	}

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
}
