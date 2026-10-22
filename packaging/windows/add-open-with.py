"""Adds "Open with Logsift" to the Windows installer that jpackage makes.

Usage: add-open-with.py <jpackage's main.wxs> <patched main.wxs>

Instead of taking over .log files (their icon, double-click and type name stay as they
are), the installer adds:
  - "Open with Logsift" to the right-click menu of files and folders (a folder opens a
    search in it)
  - Logsift to the "Open with" list of .log, .txt and .out files
Everything is under HKCU and is removed again on uninstall.

It also makes every build replace the one installed (snapshots share a version number, so
each build gets its own product code and same-version upgrades are allowed), and closes a
running Logsift before its files are replaced or removed.
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

# Asks a running Logsift to close (it saves its session), and ends it if it doesn't.
CLOSE = '''
    <util:CloseApplication Id="CloseLogsift" Target="Logsift.exe" CloseMessage="yes"
                           RebootPrompt="no" TerminateProcess="1" Timeout="5"/>
'''


def main(src, dst):
    s = open(src, encoding='utf-8').read()
    if '</Product>' not in s or '</Feature>' not in s or 'INSTALLDIR' not in s:
        sys.exit(f'{src}: not the main.wxs this script knows (no Product, Feature or INSTALLDIR)')
    s = s.replace('</Feature>', '  <ComponentRef Id="LogsiftOpenWith"/>\n    </Feature>', 1)
    s = s.replace('</Product>', COMPONENT + CLOSE + '  </Product>', 1)

    # a new product code for every build: installing the same version again is an upgrade
    s, n = re.subn(r'(<Product\b[^>]*?\bId=)"[^"]*"', r'\1"*"', s, count=1, flags=re.S)
    if n != 1:
        sys.exit(f'{src}: no Product Id to replace')
    if '<MajorUpgrade' not in s:
        sys.exit(f'{src}: no MajorUpgrade element')

    def same_version(m):
        tag = m.group(0)
        if 'AllowSameVersionUpgrades' in tag or 'AllowDowngrades="yes"' in tag:
            return tag
        return tag.replace('<MajorUpgrade', '<MajorUpgrade AllowSameVersionUpgrades="yes"', 1)
    s = re.sub(r'<MajorUpgrade\b[^>]*>', same_version, s)

    if UTIL_NS not in s:
        s, n = re.subn(r'<Wix\b', f'<Wix xmlns:util="{UTIL_NS}"', s, count=1)
        if n != 1:
            sys.exit(f'{src}: no Wix element')
    open(dst, 'w', encoding='utf-8').write(s)
    print(f'{dst}: added Open with Logsift, same-version upgrades, closing a running Logsift')
    for line in s.splitlines():
        if 'MajorUpgrade' in line or '<Product' in line or '<Wix' in line or ' Id="*"' in line:
            print('   ', line.strip())


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
