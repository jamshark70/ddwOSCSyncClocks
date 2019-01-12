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
		addr.sendBundle(latency, ['/ddwClock', id, this.beats, latency, this.tempo]);
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
				addr.sendMsg('/ddwPingReply', msg[1]);
			}, '/ddwPing');
		}
	}

	freePingResponder {
		pingResp.free;
		pingResp = nil;
	}
}

DDWSlaveClock : TempoClock {
	var <latency, <netDelay = 0, <diff, <addr, <>id;
	var clockResp, meterResp, stopResp;
	// Kalman variables
	var uncertainty, kGain,
	<>clockDriftFactor = 0.00001,  // "process noise" in Kalman literature
	<>measurementError = 0.003;  // "measurement uncertainty"
	var pingThread, pingResp;
	var <>debugInstability = 0, <>debug = false;

	// tempo, beats, seconds retained for compatibility, but they are overwritten
	*new { |tempo, beats, seconds, queueSize = 256, id|
		^super.new(tempo, beats, seconds, queueSize).id_(id).makeResponder(id)
	}

	stop {
		this.stopAliveThread;
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
		{
			diff = 1;
			uncertainty = 10000;  // no confidence in first 'diff' guess
			clockResp = OSCFunc({ |msg, time, argAddr|
				// difference = (sysclock + latency) - (time already includes latency)
				value = (SystemClock.seconds - time) + msg[3] + debugInstability.rand;
				uncertainty = uncertainty + clockDriftFactor;
				kGain = uncertainty / (uncertainty + measurementError);
				diff = diff + (kGain * (value - diff));  // "estimate current state"
				uncertainty = (1 - kGain) * uncertainty;
				if(debug) {
					[SystemClock.seconds, time, value, diff].debug("DDWSlaveClock(%)".format(id));
				};
				if(addr.isNil) {
					addr = argAddr;
					this.startAliveThread;
				} {
					this.prSync(msg, time);
				};
			}, '/ddwClock', argTemplate: argTemplate);
		}
	}

	// ['/ddwClock', id, this.beats, latency, this.tempo]
	prSync { |msg, time|
		// diff = (my time - their time);
		// 'time' is their time so it's their time + my time - their time --> my time
		// also, 'time' already includes latency so don't add/subtract it here
		SystemClock.schedAbs(time + diff - netDelay, {
			// an update might have been scheduled when you did 'clock.stop'
			if(this.isRunning) {
				// but msg[2] 'beats' does *not* account for latency; scale to tempo
				latency = msg[3];
				this.prTempo_(msg[4]).prBeats_(msg[2] + (latency * msg[4]));
			};
		});
	}
	prTempo_ { |newTempo| ^super.tempo = newTempo }
	prBeats_ { |newBeats| ^super.beats = newBeats }

	startAliveThread {
		var lastSent, count = 0;
		var uncertainty = 10000, kGain,
		processNoise = 0.0000001,  // "process noise" in Kalman literature
		measurementError = 0.00003;  // "measurement uncertainty"
		var cond = Condition.new;
		if(pingResp.isNil) {
			pingResp = OSCFunc({ |msg|
				var delta;
				if(msg[1] == count) {
					delta = SystemClock.seconds - lastSent + debugInstability.rand;
					count = count + 1;
					uncertainty = uncertainty + processNoise;
					kGain = uncertainty / (uncertainty + measurementError);
					netDelay = netDelay + (kGain * (delta - netDelay));  // "estimate current state"
					uncertainty = (1 - kGain) * uncertainty;
					if(debug) {
						[count, delta, netDelay].debug("DDWSlaveClock(%) ping".format(id));
					};
					cond.unhang;
				};
			}, '/ddwPingReply');
		};
		pingThread.stop;
		pingThread = Routine {
			// IMPORTANT: make sure there is only one pending /ping query
			// Condition should guarantee this
			netDelay = 0;
			loop {
				lastSent = SystemClock.seconds;
				addr.sendMsg('/ddwPing', count);
				cond.hang;
				rrand(0.2, 0.6).wait;
			};
		}.play(AppClock)
	}

	stopAliveThread {
		pingResp.free;
		pingResp = nil;
		pingThread.stop;
		pingThread = nil;
	}
}
