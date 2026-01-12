package co.anbora.labs.close.without.breakpoint.startup

import co.anbora.labs.close.without.breakpoint.CheckLicense
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.TimeUnit

class SetupStartup: ProjectActivity {
    override suspend fun execute(project: Project) {

        AppExecutorUtil.getAppScheduledExecutorService().schedule({
            val licensed = CheckLicense.isLicensed() ?: false

            if (!licensed && !project.isDisposed) {
                CheckLicense.requestLicense("Buy license for Close Tabs Without Breakpoints plugin")
            }
        }, 5, TimeUnit.MINUTES)
    }
}