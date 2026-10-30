"""Adds "Open with Logsift" to the Windows installer that jpackage makes.

Usage: add-open-with.py <jpackage's main.wxs> <patched main.wxs> [x64|arm64]

Instead of taking over .log files (their icon, double-click and type name stay as they
are), the installer adds:
  - "Open with Logsift" to the right-click menu of files and folders (a folder opens a
    search in it)
  - Logsift to the "Open with" list of .log, .txt and .out files
Everything is under HKCU and is removed again on uninstall.

It also makes every build replace the one installed (snapshots share a version number, so
each build gets its own product code and same-version upgrades are allowed), and closes a
running Logsift before its files are replaced or removed.

For arm64 it marks the package as an Arm64 one: jpackage always says x64, and its helper
DLL (built for Arm64 by an Arm64 JDK) then can't be loaded by the x64 helper process that
Windows starts for an x64 package ("A DLL required for this install to complete could not
be run").
"""
import re
import sys

EXE = '&quot;[INSTALLDIR]Logsift.exe&quot; &quot;%1&quot;'

COMPONENT = f'''
    <DirectoryRef Id="TARGETDIR">
      <Component Id="LogsiftOpenWith" Guid="{{6F1B7C1E-2B3A-4C55-9E57-6A0C9E1D4B21}}">
        <RegistryValue Root="HKCU" Key="Software\\Classes\\*\\shell\\Logsift" Type="string"
                       Value="Open with Logsift" KeyPath="yes"/>
        <RegistryValue Root="HKCU" Key="Software\\Classes\\*\\shell\\Logsift" Name="Icon" Type="string"
                       Value="[INSTALLDIR]Logsift.exe"/>
        <RegistryValue Root="HKCU" Key="Software\\Classes\\*\\shell\\Logsift\\command" Type="string"
                       Value="{EXE}"/>
        <RegistryValue Root="HKCU" Key="Software\\Classes\\Directory\\shell\\Logsift" Type="string"
                       Value="Open with Logsift"/>
        <RegistryValue Root="HKCU" Key="Software\\Classes\\Directory\\shell\\Logsift" Name="Icon" Type="string"
                       Value="[INSTALLDIR]Logsift.exe"/>
        <RegistryValue Root="HKCU" Key="Software\\Classes\\Directory\\shell\\Logsift\\command" Type="string"
                       Value="{EXE}"/>
        <RegistryValue Root="HKCU" Key="Software\\Classes\\Applications\\Logsift.exe" Name="FriendlyAppName"
                       Type="string" Value="Logsift"/>
        <RegistryValue Root="HKCU" Key="Software\\Classes\\Applications\\Logsift.exe\\shell\\open\\command"
                       Type="string" Value="{EXE}"/>
        <RegistryValue Root="HKCU" Key="Software\\Classes\\Applications\\Logsift.exe\\SupportedTypes"
                       Name=".log" Type="string" Value=""/>
        <RegistryValue Root="HKCU" Key="Software\\Classes\\Applications\\Logsift.exe\\SupportedTypes"
                       Name=".txt" Type="string" Value=""/>
        <RegistryValue Root="HKCU" Key="Software\\Classes\\Applications\\Logsift.exe\\SupportedTypes"
                       Name=".out" Type="string" Value=""/>
        <RegistryValue Root="HKCU" Key="Software\\Classes\\.log\\OpenWithList\\Logsift.exe" Type="string" Value=""/>
        <RegistryValue Root="HKCU" Key="Software\\Classes\\.txt\\OpenWithList\\Logsift.exe" Type="string" Value=""/>
        <RegistryValue Root="HKCU" Key="Software\\Classes\\.out\\OpenWithList\\Logsift.exe" Type="string" Value=""/>
      </Component>
    </DirectoryRef>
'''


UTIL_NS = 'http://schemas.microsoft.com/wix/UtilExtension'

# Asks a running Logsift to close (it saves its session), and ends it if it doesn't. A
# logsift-mcp a coding agent started is ended (it keeps nothing; the agent starts it again).
CLOSE = '''
    <util:CloseApplication Id="CloseLogsift" Target="Logsift.exe" CloseMessage="yes"
                           RebootPrompt="no" TerminateProcess="1" Timeout="5"/>
    <util:CloseApplication Id="CloseLogsiftMcp" Target="logsift-mcp.exe" RebootPrompt="no"
                           TerminateProcess="1" Timeout="1"/>
'''


def main(src, dst, arch='x64'):
    s = open(src, encoding='utf-8').read()
    if '</Product>' not in s or '</Feature>' not in s or 'INSTALLDIR' not in s:
        sys.exit(f'{src}: not the main.wxs this script knows (no Product, Feature or INSTALLDIR)')
    s = s.replace('</Feature>', '  <ComponentRef Id="LogsiftOpenWith"/>\n    </Feature>', 1)
    s = s.replace('</Product>', COMPONENT + CLOSE + '  </Product>', 1)

    # a new product code for every build: installing the same version again is an upgrade
    s, n = re.subn(r'(<Product\b[^>]*?\bId=)"[^"]*"', r'\1"*"', s, count=1, flags=re.S)
    if n != 1:
        sys.exit(f'{src}: no Product Id to replace')
    # jpackage's Upgrade table leaves out the version being installed (and counts it as a
    # downgrade): include it in the upgrade, so a new build of the same version replaces the old
    def upgrade_version(m):
        tag = m.group(0)
        if 'JP_UPGRADABLE_FOUND' in tag:
            return re.sub(r'IncludeMaximum="[^"]*"', 'IncludeMaximum="yes"', tag)
        if 'JP_DOWNGRADABLE_FOUND' in tag:
            return re.sub(r'IncludeMinimum="[^"]*"', 'IncludeMinimum="no"', tag)
        return tag
    s, n = re.subn(r'<UpgradeVersion\b[^>]*/>', upgrade_version, s, flags=re.S)
    if n < 2 or 'IncludeMaximum="yes"' not in s or 'IncludeMinimum="no"' not in s:
        sys.exit(f'{src}: Upgrade table not as expected')

    if arch == 'arm64':
        def arm64(m):
            tag = re.sub(r'\s+Platform="[^"]*"', '', m.group(0))
            # Arm64 packages need Windows Installer 5.0
            tag = re.sub(r'InstallerVersion="(\d+)"',
                         lambda v: f'InstallerVersion="{max(500, int(v.group(1)))}"', tag)
            if 'InstallerVersion=' not in tag:
                tag = tag.replace('<Package', '<Package InstallerVersion="500"', 1)
            return tag.replace('<Package', '<Package Platform="arm64"', 1)
        s, n = re.subn(r'<Package\b[^>]*>', arm64, s, count=1, flags=re.S)
        if n != 1:
            sys.exit(f'{src}: no Package element')
    elif arch != 'x64':
        sys.exit(f'unknown architecture: {arch}')

    if UTIL_NS not in s:
        s, n = re.subn(r'<Wix\b', f'<Wix xmlns:util="{UTIL_NS}"', s, count=1)
        if n != 1:
            sys.exit(f'{src}: no Wix element')
    open(dst, 'w', encoding='utf-8').write(s)
    print(f'{dst}: added Open with Logsift, same-version upgrades, closing a running Logsift ({arch})')


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2], *sys.argv[3:4])
