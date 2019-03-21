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
	thread, <>addr, tempoResp;
	var <>latency = 0.2, <>period = 0.05;

	*new { |tempo, beats, seconds, queueSize = 256, id(UniqueID.next)|
		^super.new(tempo, beats, seconds, queueSize).id_(id)
	}

	init { arg tempo, beats, seconds, queueSize;
		super.init(tempo, beats, seconds, queueSize);
		NetAddr.broadcastFlag = true;
		addr = NetAddr("255.255.255.255", 57120);  // probably change port later
		this.makePingResponder.startAliveThread;
		// id is not known until slightly later but I want to use it to filter tempo messages
		{
			tempoResp = OSCFunc({ |msg|
				this.tempo = msg[2];
			}, '/ddwSlaveClockTempo_', argTemplate: [id]);
		}.defer(0);
		ShutDown.add(this);
	}

	// removes the clock, so, clean up everything
	stop {
		tempoResp.free;
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
		// msg[2] should be the beats in the future, after 'latency' seconds
		// simplifies slaveclock's calculation
		addr.sendBundle(latency,
			['/ddwClock', id, this.beats + (latency * this.tempo), latency, this.tempo, SystemClock.seconds]);
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
	var <>bias = 0, <latency, <netDelay = 0, <diff, <addr, <>id;
	var clockResp, meterResp, stopResp;
	// Kalman variables
	var uncertainty, kGain,
	<>clockDriftFactor = 0.00001,  // "process noise" in Kalman literature
	<>measurementError = 0.003;  // "measurement uncertainty"
	var pingThread, pingResp, postPingWarning = true;
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

	tempo_ { |newTempo|
		if(newTempo.isNumber.not) {
			Error("Tempo % should be a number".format(newTempo)).throw;
		};
		if(addr.notNil) {
			addr.sendMsg('/ddwSlaveClockTempo_', id, newTempo.asFloat);
		};
		super.tempo = newTempo;
	}
	beatsPerBar_ {
		Error("DDWSlaveClock cannot set beatsPerBar directly (id = %)".format(id)).throw;
	}
	beats_ {
		Error("DDWSlaveClock cannot set beats directly (id = %)".format(id)).throw;
	}

	makeResponder { |argId|
		var argTemplate, value, waiting = true, test;
		id = argId;
		argTemplate = if(id.notNil) { [id] } { nil };
		stopResp.free;
		stopResp = OSCFunc({ |msg|
			"Master clock ID % stopped. DDWSlaveClock is now free-running.".format(id).warn;
			postPingWarning = false;
			this.changed(\ddwSlaveClockUnsynced);
		}, '/ddwMasterStopped', argTemplate: argTemplate);
		meterResp.free;
		meterResp = OSCFunc({ |msg|
			this.setMeterAtBeat(msg[2], msg[3]);
		}, '/ddwClockMeter', argTemplate: argTemplate);
		clockResp.free;
		case
		// default setup: one master, slave doesn't specify ID
		// first tick message from the master should set the ID
		{ id.isNil or: { addr.isNil } } {
			clockResp = OSCFunc({ |msg, time, argAddr|
				"DDWSlaveClock syncing to id % at NetAddr(%, %)\n".postf(msg[1], argAddr.ip, argAddr.port);
				addr = argAddr;
				this.startAliveThread;
				this.makeResponder(msg[1]);
			}, '/ddwClock', argTemplate: argTemplate);
		}
		{
			diff = 1;
			uncertainty = 10000;  // no confidence in first 'diff' guess
			test = Routine { |time|
				// at first, 'diff' hasn't been calculated
				// if the slave's SystemClock is later than the master's,
				// the slave never initializes and ignores all sync messages
				// so, guarantee at least 30 cycles of the Kalman filter
				30.do { time = true.yield };
				this.changed(\ddwSlaveClockSynced, id);
				"DDWSlaveClock(%) synced\n".postf(id);
				loop { time = (SystemClock.seconds < (time + diff - netDelay - bias + latency)).yield };
			};
			clockResp = OSCFunc({ |msg, time, argAddr|
				latency = msg[3];
				time = msg[5];  // SystemClock.seconds from master
				// if something blocks the language for awhile, e.g. MIDI init,
				// SystemClock.seconds will be late and mess up the clock difference
				// we should ignore sync messages that arrived too late to sync on time
				// note that 'diff' NO LONGER includes latency (msg[3]) already
				if(test.next(time)) {
					value = (SystemClock.seconds - time) + msg[3] + debugInstability.value;
					uncertainty = uncertainty + clockDriftFactor;
					kGain = uncertainty / (uncertainty + measurementError);
					diff = diff + (kGain * (value - diff));  // "estimate current state"
					uncertainty = (1 - kGain) * uncertainty;
					if(debug) {
						[SystemClock.seconds, time + diff - netDelay - bias /*- msg[3]*/, time, value, diff].debug("DDWSlaveClock(%)".format(id));
					};
					this.prSync(msg, time);
					postPingWarning = true;
					if(waiting) {
						// should not send this until after the first prSync
						this.changed(\ddwSlaveClockId, id);
						waiting = false;
					};
				} {
					msg.debug(
						"DDWSlaveClock(%): message arrived late; local SystemClock = %; remote time = %; remote time adjusted = %".format(id, SystemClock.seconds, time, time + diff - netDelay - bias + latency)
					)
				};
			}, '/ddwClock', argTemplate: argTemplate);

		}
	}

	// ['/ddwClock', id, this.beats, latency, this.tempo]
	prSync { |msg, time|
		// diff = (my time - their time);
		// 'time' is their time so it's their time + my time - their time --> my time
		// also, 'time' already includes latency so don't add/subtract it here
		SystemClock.schedAbs(time + diff - netDelay - bias + latency, {
			// an update might have been scheduled when you did 'clock.stop'
			if(this.isRunning) {
				latency = msg[3];
				this.prTempo_(msg[4]).prBeats_(msg[2]);
			};
		});
	}
	prTempo_ { |newTempo|
		if(newTempo != this.tempo) {
			super.tempo = newTempo
		};
	}
	prBeats_ { |newBeats| ^super.beats = newBeats }

	startAliveThread {
		var lastSent, count = 0;
		var uncertainty = 10000, kGain;
		var cond = Condition.new,
		// we need a timeout because I've observed dropped packets in real networks
		// if a ping request or reply gets dropped, then this thread would hang forever
		timeoutFunc = { |currentCount|
			// if the ping reply doesn't come back in 2 seconds, something is wrong
			AppClock.sched(2.0, {
				if(postPingWarning and: { count == currentCount and: { this.isRunning } }) {
					"DDWSlaveClock(%): Ping at count % timed out after 2 seconds"
					.format(id, count).warn;
					cond.unhang;
				};
			});
		};
		if(pingResp.isNil) {
			pingResp = OSCFunc({ |msg|
				var delta;
				if(msg[1] == count) {
					delta = 0.5 * (SystemClock.seconds - lastSent
						+ debugInstability.value + debugInstability.value);
					count = count + 1;
					uncertainty = uncertainty + clockDriftFactor;
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
				timeoutFunc.value(count);
				cond.hang;
				rrand(0.4, 0.8).wait;
			};
		}.play(AppClock);
	}

	stopAliveThread {
		pingResp.free;
		pingResp = nil;
		pingThread.stop;
		pingThread = nil;
	}

	isRunning { ^ptr.notNil }  // unbelievably, TempoClock doesn't supply this
}
