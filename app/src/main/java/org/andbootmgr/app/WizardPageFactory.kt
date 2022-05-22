package org.andbootmgr.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.io.SuFile
import com.topjohnwu.superuser.io.SuFileInputStream
import com.topjohnwu.superuser.io.SuFileOutputStream
import org.andbootmgr.app.ui.theme.AbmTheme
import java.io.File
import java.io.IOException
import java.io.OutputStream

class WizardPageFactory(private val vm: WizardActivityState) {
	fun get(flow: String): List<IWizardPage> {
		return listOf(WizardPage("start",
			NavButton("Cancel") { it.finish() },
			NavButton("Next") { it.navigate("input") })
		{
			Start(vm)
		}, WizardPage("input",
			NavButton("Prev") { it.navigate("start") },
			NavButton("Next") { it.navigate("select") }
		) {
			Input(vm)
		}, WizardPage("select",
			NavButton("Prev") { it.navigate("input") },
			NavButton("") {}
		) {
			Select(vm)
		}, WizardPage("flash",
			NavButton("") {},
			NavButton("") {}
		) {
			Flash(vm)
		})
	}
}

@Composable
fun Start(vm: WizardActivityState) {
	Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
		modifier = Modifier.fillMaxSize()
	) {
		Text("Welcome to ABM!")
		Text("This will install ABM + DroidBoot")
	}
}

@Composable
fun Input(vm: WizardActivityState) {
	Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
		modifier = Modifier.fillMaxSize()
	) {
		var text by remember { mutableStateOf("Android") }
		vm.texts["OsName"] = text.trim()
		val e = text.isBlank() || !text.matches(Regex("[0-9A-Za-z]+"))

		Text("Please enter an name for the currently running operating system. You may choose as you wish.", textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 5.dp))
		TextField(
			value = text,
			onValueChange = {
				text = it
				vm.texts["OsName"] = it.trim()
			},
			label = { Text("OS name") },
			isError = e
		)
		if (e) {
			vm.nextText.value = ""
			vm.onNext.value = {}
			Text("Invalid input", color = MaterialTheme.colorScheme.error)
		} else {
			vm.nextText.value = "Next"
			vm.onNext.value = { it.navigate("select") }
			Text("") // Budget spacer
		}
	}
}


@Composable
fun Select(vm: WizardActivityState) {
	val nextButtonAvailable = remember { mutableStateOf(false) }
	val flashType = "DroidBootFlashType"

	Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
		modifier = Modifier.fillMaxSize()
	) {
		Icon(
			painterResource(R.drawable.ic_droidbooticon),
			"DroidBoot Icon",
			Modifier.defaultMinSize(32.dp, 32.dp)
		)

		if (nextButtonAvailable.value) {
			Text("Successfully selected.")
			vm.nextText.value = "Next"
			vm.onNext.value = { it.navigate("flash") }
		} else {
			Text("Please choose DroidBoot image!")
			Button(onClick = {
				vm.activity.chooseFile("*/*") {
					vm.flashes[flashType] = it
					nextButtonAvailable.value = true
				}
			}) {
				Text("Choose file")
			}
		}
	}
}

@Composable
fun Flash(vm: WizardActivityState) {
	val flashType = "DroidBootFlashType"
	Terminal(vm) { terminal ->
		terminal.add("Preparing file system...")
		if (vm.logic.mounted) {
			terminal.add("-- inconsistent mount state, aborting")
			return@Terminal
		}
		if (!SuFile.open(vm.logic.abmDir.toURI()).mkdir()) {
			terminal.add("-- failed to create /data/abm, aborting")
			return@Terminal
		}
		if (!SuFile.open(vm.logic.abmBootset.toURI()).mkdir()) {
			terminal.add("-- failed to create mount point, aborting")
			return@Terminal
		}
		if (!SuFile.open(File(vm.logic.abmBootset, ".NOT_MOUNTED").toURI()).createNewFile()) {
			terminal.add("-- failed to create placeholder, aborting")
			return@Terminal
		}
		if (!vm.logic.mount(vm.deviceInfo!!)) {
			terminal.add("-- failed to mount, aborting")
			return@Terminal
		}
		if (SuFile.open(File(vm.logic.abmBootset, ".NOT_MOUNTED").toURI()).exists()) {
			terminal.add("-- inconsistent mount failure, aborting")
			return@Terminal
		}
		if (!SuFile.open(vm.logic.abmDb.toURI()).mkdir()) {
			terminal.add("-- failed to create db directory, aborting")
			vm.logic.unmount(vm.deviceInfo)
			return@Terminal
		}
		if (!SuFile.open(vm.logic.abmEntries.toURI()).mkdir()) {
			terminal.add("-- failed to create entries directory, aborting")
			vm.logic.unmount(vm.deviceInfo)
			return@Terminal
		}
		val o = SuFileOutputStream.open(File(vm.logic.abmDir, "codename.cfg"))
		o.write(vm.deviceInfo.codename.toByteArray())
		o.flush()
		o.close()
		terminal.add("Building configuration...")
		val db = ConfigFile()
		db["default"] = "Entry 01"
		db["timeout"] = "5"
		db.exportToFile(File(vm.logic.abmDb, "db.conf"))
		val entry = ConfigFile()
		entry["title"] = vm.texts["OsName"]!!
		entry["linux"] = "null"
		entry["initrd"] = "null"
		entry["dtb"] = "null"
		entry["options"] = "null"
		entry["xtype"] = "droid"
		entry["xsystem"] = "real"
		entry["xdata"] = "real"
		entry.exportToFile(File(vm.logic.abmEntries, "hjacked.conf"))
		terminal.add("Flashing DroidBoot...")
		val f = SuFile.open(vm.deviceInfo.blBlock)
		if (!f.canWrite())
			terminal.add("Note: probably cannot write to bootloader")
		vm.copyPriv(SuFileInputStream.open(vm.deviceInfo.blBlock), File(vm.logic.abmDir, "backup_lk.img"))
		try {
			vm.copyPriv(vm.flashStream(flashType), File(vm.deviceInfo.blBlock))
		} catch (e: IOException) {
			terminal.add("-- Failed to flash bootloader, cause:")
			terminal.add(if (e.message != null) e.message!! else "(null)")
			terminal.add("-- Please consult documentation to finish the install.")
		}
		terminal.add("-- Done.")
		vm.logic.unmount(vm.deviceInfo)
		vm.activity.runOnUiThread {
			vm.nextText.value = "Finish"
			vm.onNext.value = {
				it.finish()
			}
		}

	}
}

@Composable
@Preview
fun Preview() {
	val vm = WizardActivityState("null")
	AbmTheme {
		Surface(
			modifier = Modifier.fillMaxSize(),
			color = MaterialTheme.colorScheme.background
		) {
			Input(vm)
		}
	}
}