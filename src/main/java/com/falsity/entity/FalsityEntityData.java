package com.falsity.entity;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;

/**
 * The state Falsity keeps in sync between server and client.
 *
 * <p>The server owns every value here; clients only read them. The owner is
 * stored as a string so an unknown or corrupt value can never throw while the
 * client is decoding a packet.
 */
public final class FalsityEntityData {
	/** The UUID of the player Falsity has bonded with, or an empty string. */
	public static final EntityDataAccessor<String> OWNER =
		SynchedEntityData.defineId(FalsityEntity.class, EntityDataSerializers.STRING);

	/** Whether Falsity has been told to stay put. */
	public static final EntityDataAccessor<Boolean> SITTING =
		SynchedEntityData.defineId(FalsityEntity.class, EntityDataSerializers.BOOLEAN);

	private FalsityEntityData() {
	}
}
