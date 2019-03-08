TestDDWOSCClocks : UnitTest {
	test_ddwSyncClocks_acquireAndMaintainSync {
		var master, slave, cond = Condition.new, resp;

		this.assertEquals(NetAddr.langPort, 57120,
			"sclang's port must be 57120 to run this test");

		master = DDWMasterClock(2, beats: 10);
		0.3.wait;  // do not start slave clock on an even beat

		// empirically I found network jitter may be over 100 ms
		// we should test stability
		slave = DDWSlaveClock.new.debugInstability_(0.15);
		// if beats are the same initially, then sync might be an accident
		this.assert(master.beats - slave.beats > 10, "Slave clock is initially out of sync");

		resp = SimpleController(slave).put(\ddwSlaveClockSynced, {
			cond.unhang
		})
		.put(\stop, { resp.remove });  // prepare for cleanup at end
		cond.hang;

		// now we should be mostly synced, but needs a little time to stabilize
		5.0.wait;

		this.assertFloatEquals(
			master.beats, slave.beats,
			"DDWMasterClock and DDWSlaveClock beats should be synced within a few ms",
			within: 0.01
		);

		slave.stop;
		master.stop;
	}
}
