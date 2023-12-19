//! Windows-specific code for the Enso installer.

use crate::prelude::*;
use std::ffi::c_void;
use std::os::windows::ffi::OsStringExt;


use windows::core::PCWSTR;
use windows::Win32::UI::Shell;
use winreg::RegKey;
use winreg::RegValue;

pub mod app_paths;
pub mod prog_id;
pub mod registry;
pub mod resource;
pub mod shortcut;
pub mod uninstall;

/// Open the `HKEY_CURRENT_USER\Software\Classes` key for reading and writing.
///
/// This is where the programmatic identifiers (ProgIDs) of file types and URL protocols are
/// stored.
pub fn classes_key() -> Result<RegKey> {
    RegKey::predef(HKEY_CURRENT_USER)
        .open_subkey_with_flags(r"Software\Classes", KEY_READ | KEY_WRITE)
        .with_context(|| format!(r#"Failed to open HKEY_CURRENT_USER\Software\Classes key."#))
}


/// Get the local user's Desktop directory path.
///
/// Please note that this is not the same as the `%USERPROFILE%\Desktop`!
/// The desktop location can be redirected to a different location, e.g. OneDrive.
pub fn desktop() -> Result<PathBuf> {
    dirs::desktop_dir().context("Failed to get the local user's Desktop directory path.")
}

/// Start Menu programs location.
pub fn start_menu_programs() -> Result<PathBuf> {
    known_folder(Shell::FOLDERID_Programs)
        .context("Failed to get the local user's Start Menu programs directory path.")
}

/// Query WinAPI for the path of a known folder.
#[context("Failed to get the path of a known folder by GUID {folder_id:?}.")]
pub fn known_folder(folder_id: windows::core::GUID) -> Result<PathBuf> {
    unsafe {
        let path = Shell::SHGetKnownFolderPath(&folder_id, default(), None)?;
        let ret = OsString::from_wide(path.as_wide());
        windows::Win32::System::Com::CoTaskMemFree(Some(path.0 as *mut c_void));
        Ok(ret.into())
    }
}

/// Notify shell that file associations have changed.
///
/// This is needed to make the changes visible without restarting the system. See:
/// * https://learn.microsoft.com/en-us/windows/win32/shell/fa-file-types#setting-optional-subkeys-and-file-type-extension-attributes
/// * https://learn.microsoft.com/en-us/windows/win32/api/shlobj_core/nf-shlobj_core-shchangenotify
pub fn refresh_file_associations() {
    // All direct WinAPI calls are unsafe (Rust-wise), however this particular one should never be
    // able to cause any harm. It cannot even fail API-wise (it returns `void`).
    unsafe {
        Shell::SHChangeNotify(Shell::SHCNE_ASSOCCHANGED, Shell::SHCNF_FLAGS(0), None, None);
    }
}

/// Application-defined resource (raw data).
///
/// See https://learn.microsoft.com/en-us/windows/win32/menurc/resource-types
pub const RT_RCDATA: PCWSTR = PCWSTR(10 as _);


/// Path to icon, as used in various places in registry (e.g. `DefaultIcon`).
#[derive(Debug, Clone)]
pub struct Icon {
    /// Path to the executable (or DLL) containing the icon.
    pub executable_path: PathBuf,
    /// Index of the icon in the executable.
    pub icon_index:      u32,
}

impl Display for Icon {
    fn fmt(&self, f: &mut Formatter<'_>) -> std::fmt::Result {
        write!(f, r#""{}",{}"#, self.executable_path.display(), self.icon_index)
    }
}

impl winreg::types::ToRegValue for Icon {
    fn to_reg_value(&self) -> RegValue {
        self.to_string().to_reg_value()
    }
}

impl Icon {
    /// Use the first icon in the given PE file.
    pub fn new(executable_path: impl Into<PathBuf>) -> Self {
        Self { executable_path: executable_path.into(), icon_index: 0 }
    }

    /// Store under the `DefaultIcon` subkey of the registry key.
    pub fn write_default_icon_subkey(&self, key: &RegKey) -> Result {
        let (icon_key, _disposition) = registry::create_subkey(key, "DefaultIcon")?;
        registry::set_value(&icon_key, "", self)
    }
}


/// A simple command that can be used by the shell to open a file/url with our application.
///
/// It will simply start the application, giving the file/url as the first argument.
#[derive(Debug, Clone)]
pub struct PlainOpenCommand {
    /// Path to the executable.
    pub executable_path: PathBuf,
}

impl Display for PlainOpenCommand {
    fn fmt(&self, f: &mut Formatter<'_>) -> std::fmt::Result {
        // Note that the quotes around both paths are required for paths with spaces.
        write!(f, r#""{}" "%1""#, self.executable_path.display())
    }
}

impl winreg::types::ToRegValue for PlainOpenCommand {
    fn to_reg_value(&self) -> RegValue {
        self.to_string().to_reg_value()
    }
}

impl PlainOpenCommand {
    /// Create a new command that will open the given executable.
    pub fn new(executable_path: impl Into<PathBuf>) -> Self {
        Self { executable_path: executable_path.into() }
    }

    /// Set this as a shell command to open given entity (represented by the key).
    ///
    /// The command will be stored under the `shell\open\command` subkey of the given key.
    #[context("Failed to set shell open command.")]
    pub fn set_as_shell_open_command(&self, key: &RegKey) -> Result {
        let command_key = registry::create_subkey(key, r"shell\open\command")?.0;
        registry::set_value(&command_key, "", self)
    }
}
