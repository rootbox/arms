package com.arms.androidauto.desktop
import com.arms.androidauto.desktop.storage.FileCredentialStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import java.nio.file.Files
class FileCredentialStoreTest {
    @Test fun `자격증명과 sid 라운드트립 및 clear`() {
        val st = FileCredentialStore(Files.createTempDirectory("cred").toFile())
        assertNull(st.get()); assertNull(st.getSid())
        st.save(com.arms.androidauto.core.network.SynologyCredentials("https://nas:5001","u","p"))
        assertEquals("u", st.get()?.account)
        st.saveSid("SID123"); assertEquals("SID123", st.getSid())
        st.clearSid(); assertNull(st.getSid()); assertEquals("u", st.get()?.account) // sid만 지움
        st.clear(); assertNull(st.get())
    }
}
