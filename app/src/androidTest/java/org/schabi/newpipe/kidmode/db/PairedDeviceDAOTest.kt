package org.schabi.newpipe.kidmode.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.schabi.newpipe.database.AppDatabase

class PairedDeviceDAOTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: PairedDeviceDAO

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        dao = db.pairedDeviceDAO()
    }

    @After
    fun closeDb() {
        db.close()
    }

    private fun newDevice(deviceId: String = "device-1") = PairedDeviceEntity(
        deviceId = deviceId,
        deviceName = "Parent's phone",
        sharedSecret = "iv:ciphertext",
        pairedAt = 1L
    )

    @Test
    fun lookingUpAnUnknownDeviceIdReturnsNullPromptly() {
        // Regression check for the Flowable-hangs-forever trap ApprovalRequestDAO.getByIdOnce
        // was added to fix -- getActiveByDeviceId must be a plain nullable query, not a Flowable.
        assertNull(dao.getActiveByDeviceId("does-not-exist"))
    }

    @Test
    fun anInsertedDeviceCanBeLookedUpByDeviceId() {
        dao.insert(newDevice())
        val found = dao.getActiveByDeviceId("device-1")
        assertEquals("Parent's phone", found?.deviceName)
    }

    @Test
    fun aRevokedDeviceIsNotReturnedAsActive() {
        dao.insert(newDevice())
        dao.revoke("device-1")
        assertNull(dao.getActiveByDeviceId("device-1"))
    }

    @Test
    fun revokeReportsHowManyRowsChanged() {
        dao.insert(newDevice())
        val rowsChanged = dao.revoke("device-1")
        assertEquals(1, rowsChanged)
        assertEquals(0, dao.revoke("device-1"))
    }

    @Test
    fun getActiveListsNonRevokedDevices() {
        dao.insert(newDevice("device-1"))
        dao.insert(newDevice("device-2"))
        dao.revoke("device-2")

        val active = dao.getActive().blockingFirst()

        assertEquals(1, active.size)
        assertTrue(active.all { !it.revoked })
    }
}
