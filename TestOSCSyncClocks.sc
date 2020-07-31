TestDDWOSCClocks : UnitTest {
	test_ddwSyncClocks_acquireAndMaintainSync {
		var leader, follower, cond = Condition.new, resp, sum;

		this.assertEquals(NetAddr.langPort, 57120,
			"sclang's port must be 57120 to run this test");

		// sync messages faster, wait less time below
		leader = DDWLeadClock(2, beats: 10).period_(0.2);
		0.3.wait;  // do not start follower clock on an even beat

		// empirically I found network jitter may be over 100 ms
		// we should test stability under worse conditions than we expect
		follower = DDWFollowClock.new.debugInstability_({ rrand(0.01, 0.184) });
		// if beats are the same initially, then sync might be an accident
		this.assert(leader.beats - follower.beats > 10, "Follower clock is initially out of sync");

		resp = SimpleController(follower).put(\ddwFollowClockSynced, {
			cond.unhang
		})
		.put(\stop, { resp.remove });  // prepare for cleanup at end
		cond.hang;

		// now we should be mostly synced, but needs a little time to stabilize
		// unfortunately I found that you can't cheat and run the test faster
		// leader's period and latency should be > maximum debugInstability
		10.0.debug("synced but waiting a bit more").wait;

		"Measuring variance".postln;
		sum = 0.0;
		20.do {
			sum = sum + (leader.beats - follower.beats).squared;
			rrand(0.3, 0.7).wait;
		};

		this.assertFloatEquals(
			// leader.beats, follower.beats,
			sum / 20.0, 0.0,
			// note that variance is much smaller than actual error
			// so we set the limit very low
			"DDWLeadClock and DDWFollowClock variance should be < 0.003 (measurementError)",
			within: 0.003
		);

		follower.stop;
		leader.stop;
	}
}
