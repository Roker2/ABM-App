package org.andbootmgr.app

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.io.SuFile
import com.topjohnwu.superuser.io.SuFileInputStream
import com.topjohnwu.superuser.io.SuFileOutputStream
import org.andbootmgr.app.util.AbmTheme
import org.andbootmgr.app.util.ConfigFile
import org.andbootmgr.app.util.SDUtils
import java.io.File
import java.io.IOException

class DroidBootWizardPageFactory(private val vm: WizardActivityState) {
	fun get(): List<IWizardPage> {
		return listOf(WizardPage("start",
			NavButton(vm.activity.getString(R.string.cancel)) { it.startActivity(Intent(it, MainActivity::class.java)); it.finish() },
			NavButton(vm.activity.getString(R.string.next)) { it.navigate("input") })
		{
			Start(vm)
		}, WizardPage("input",
			NavButton(vm.activity.getString(R.string.prev)) { it.navigate("start") },
			NavButton(vm.activity.getString(R.string.next)) { it.navigate(if (!vm.deviceInfo!!.isBooted(vm.logic)) "select" else "flash") }
		) {
			Input(vm)
		}, WizardPage("select",
			NavButton(vm.activity.getString(R.string.prev)) { it.navigate("input") },
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
private fun Start(vm: WizardActivityState) {
	Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
		modifier = Modifier.fillMaxSize()
	) {
		Text(stringResource(R.string.welcome_text))
		Text(
			if (vm.deviceInfo!!.isBooted(vm.logic)){
				stringResource(R.string.install_abm)
			} else {
				stringResource(R.string.install_abm_dboot)
			}
		)
		if (vm.deviceInfo.metaonsd){
			Text(stringResource(R.string.sd_erase1))
			Text(stringResource(R.string.sd_erase2))
		}
	}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Input(vm: WizardActivityState) {
	Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
		modifier = Modifier.fillMaxSize()
	) {
		var text by remember { mutableStateOf(vm.activity.getString(R.string.android)) }
		vm.texts["OsName"] = text.trim()
		val e = text.isBlank() || !text.matches(Regex("[0-9A-Za-z]+"))

		Text(stringResource(R.string.enter_name_for_current), textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 5.dp))
		TextField(
			value = text,
			onValueChange = {
				text = it
				vm.texts["OsName"] = it.trim()
			},
			label = { Text(stringResource(R.string.os_name)) },
			isError = e
		)
		if (e) {
			vm.nextText.value = ""
			vm.onNext.value = {}
			Text(stringResource(R.string.invalid_in), color = MaterialTheme.colorScheme.error)
		} else {
			vm.nextText.value = stringResource(id = R.string.next)
			vm.onNext.value = { it.navigate("select") }
			Text("") // Budget spacer
		}
	}
}


@Composable
private fun Select(vm: WizardActivityState) {
	val nextButtonAvailable = remember { mutableStateOf(false) }
	val flashType = "DroidBootFlashType"

	Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
		modifier = Modifier.fillMaxSize()
	) {
		Icon(
			painterResource(R.drawable.ic_droidbooticon),
			stringResource(R.string.droidboot_icon_content_desc),
			Modifier.defaultMinSize(32.dp, 32.dp)
		)

		if (nextButtonAvailable.value) {
			Text(stringResource(id = R.string.successfully_selected))
			vm.nextText.value = stringResource(id = R.string.next)
			vm.onNext.value = { it.navigate("flash") }
		} else {
			Text(stringResource(R.string.choose_droidboot))
			Button(onClick = {
				vm.activity.chooseFile("*/*") {
					vm.flashes[flashType] = it
					nextButtonAvailable.value = true
				}
			}) {
				Text(stringResource(id = R.string.choose_file))
			}
		}
	}
}

@Composable
private fun Flash(vm: WizardActivityState) {
	val flashType = "DroidBootFlashType"
	Terminal(vm) { terminal ->
		terminal.add("Preparing file system...")
		if (vm.logic.mounted) {
			terminal.add("-- inconsistent mount state, aborting")
			return@Terminal
		}
		if (!SuFile.open(vm.logic.abmDir.toURI()).exists()) {
			if (!SuFile.open(vm.logic.abmDir.toURI()).mkdir()) {
				terminal.add("-- failed to create /data/abm, aborting")
				return@Terminal
			}
		}
		if (!SuFile.open(vm.logic.abmBootset.toURI()).exists()) {
			if (!SuFile.open(vm.logic.abmBootset.toURI()).mkdir()) {
				terminal.add("-- failed to create mount point, aborting")
				return@Terminal
			}
		}

		if (!SuFile.open(File(vm.logic.abmBootset, ".NOT_MOUNTED").toURI()).exists()) {
			if (!SuFile.open(File(vm.logic.abmBootset, ".NOT_MOUNTED").toURI()).createNewFile()) {
				terminal.add("-- failed to create placeholder, aborting")
				return@Terminal
			}
		}

		if (vm.deviceInfo!!.metaonsd) {
			var meta = SDUtils.generateMeta(vm.deviceInfo.bdev, vm.deviceInfo.pbdev)
			if (meta == null) {
				terminal.add("-- failed to get meta, aborting")
				return@Terminal
			}
			if (Shell.cmd(SDUtils.umsd(meta)).to(terminal).exec().isSuccess) {
				terminal.add("-- warning: failed unmount drive")
			}
			if (!Shell.cmd("sgdisk --mbrtogpt --clear ${vm.deviceInfo.bdev}").to(terminal)
					.exec().isSuccess
			) {
				terminal.add("-- failed to create partition table, aborting")
				return@Terminal
			}
			meta = SDUtils.generateMeta(vm.deviceInfo.bdev, vm.deviceInfo.pbdev)
			val r = Shell.cmd(
				SDUtils.umsd(meta!!) + " && " + (meta.dump(0) as SDUtils.Partition.FreeSpace)
					.create(
						0,
						(meta.sectors - 2048) / 41 + 2048,
						"8301",
						"abm_settings"
					)
			).to(terminal).exec()
			if (r.out.join("\n").contains("old")) {
				terminal.add("-- Please reboot AS SOON AS POSSIBLE!!!")
			}
			if (r.isSuccess) {
				terminal.add("-- Done.")
			} else {
				terminal.add("-- failed to create metadata partition.")
				return@Terminal
			}
		}

		if (!vm.logic.mount(vm.deviceInfo)) {
			terminal.add("-- failed to mount, aborting")
			return@Terminal
		}
		if (SuFile.open(File(vm.logic.abmBootset, ".NOT_MOUNTED").toURI()).exists()) {
			terminal.add("-- inconsistent mount failure, aborting")
			return@Terminal
		}

		if (!SuFile.open(vm.logic.abmDb.toURI()).exists()) {
			if (!SuFile.open(vm.logic.abmDb.toURI()).mkdir()) {
				terminal.add("-- failed to create db directory, aborting")
				vm.logic.unmount(vm.deviceInfo)
				return@Terminal
			}
		}
		if (!SuFile.open(vm.logic.abmEntries.toURI()).exists()) {
			if (!SuFile.open(vm.logic.abmEntries.toURI()).mkdir()) {
				terminal.add("-- failed to create entries directory, aborting")
				vm.logic.unmount(vm.deviceInfo)
				return@Terminal
			}
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
		entry["linux"] = "real/kernel"
		entry["initrd"] = "real/initrd.cpio.gz"
		entry["dtb"] = "real/dtb.dtb"
		entry["options"] = "REPLACECMDLINE"
		entry["xtype"] = "droid"
		entry["xpart"] = "real"
		entry.exportToFile(File(vm.logic.abmEntries, "hijacked.conf"))
		if (!vm.deviceInfo.isBooted(vm.logic)) {
			terminal.add("Flashing DroidBoot...")
			val f = SuFile.open(vm.deviceInfo.blBlock)
			if (!f.canWrite())
				terminal.add("Note: probably cannot write to bootloader")
			vm.copyPriv(
				SuFileInputStream.open(vm.deviceInfo.blBlock),
				File(vm.logic.abmDir, "backup_lk.img")
			)
			try {
				vm.copyPriv(vm.flashStream(flashType), File(vm.deviceInfo.blBlock))
			} catch (e: IOException) {
				terminal.add("-- Failed to flash bootloader, cause:")
				terminal.add(if (e.message != null) e.message!! else "(null)")
				terminal.add("-- Please consult documentation to finish the install.")
			}
		}
		if (vm.deviceInfo.postInstallScript) {
			terminal.add("Device setup...")
			Shell.cmd(
				"BOOTED=${vm.deviceInfo.isBooted(vm.logic)} " +
				"${File(vm.logic.assetDir, "Scripts/install/${vm.deviceInfo.codename}.sh").absolutePath} hijacked"
			).to(terminal).exec()
		}
		terminal.add("-- Done.")
		vm.logic.unmount(vm.deviceInfo)
		vm.activity.runOnUiThread {
			vm.btnsOverride = true
			vm.nextText.value = vm.activity.getString(R.string.finish)
			vm.onNext.value = {
				if (vm.deviceInfo.isBooted(vm.logic)) {
					it.startActivity(Intent(it, MainActivity::class.java))
				}
				it.finish()
			}
		}
	}
}

@Composable
@Preview
private fun Preview() {
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