package org.schabi.newpipe.kidmode.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.schabi.newpipe.database.AppDatabase

class ParentPairingDAOTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: ParentPairingDAO

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        dao = db.parentPairingDAO()
    }

    @After
    fun closeDb() {
        db.close()
    }

    private fun newPairing(kidDeviceId: String = "kid-device-1") = ParentPairingEntity(
        kidDeviceId = kidDeviceId,
        kidDeviceName = "Kid's phone",
        host = "192.168.1.42",
        port = 46821,
        sharedSecret = "iv:ciphertext",
        pairedAt = 1L
    )

    @Test
    fun lookingUpAnUnknownUidReturnsNullPromptly() {
        // Regression check for the Flowable-hangs-forever trap ApprovalRequestDAO.getByIdOnce
        // was added to fix -- getByIdOnce must be a plain nullable query, not a Flowable.
        assertNull(dao.getByIdOnce(999L))
    }

    @Test
    fun anInsertedPairingCanBeLookedUpByUid() {
        val uid = dao.insert(newPairing())
        val found = dao.getByIdOnce(uid)
        assertEquals("Kid's phone", found?.kidDeviceName)
        assertEquals("192.168.1.42", found?.host)
        assertEquals(46821, found?.port)
    }

    @Test
    fun deleteReportsHowManyRowsChanged() {
        val uid = dao.insert(newPairing())
        assertEquals(1, dao.delete(uid))
        assertEquals(0, dao.delete(uid))
        assertNull(dao.getByIdOnce(uid))
    }

    @Test
    fun getAllListsInsertedPairings() {
        dao.insert(newPairing("kid-device-1"))
        dao.insert(newPairing("kid-device-2"))

        val all = dao.getAll().blockingFirst()

        assertEquals(2, all.size)
    }
}
