package inc.flide.vim8.ime.speech

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle

/** Transparent trampoline: an input method cannot show the runtime permission dialog itself. */
class MicPermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (hasPermission(this)) {
            finish()
        } else {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        finish()
    }

    companion object {
        fun hasPermission(context: Context) =
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

        fun request(context: Context) {
            context.startActivity(
                Intent(
                    context,
                    MicPermissionActivity::class.java
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
