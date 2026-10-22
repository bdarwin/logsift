"""Adds "Open with Logsift" to the Windows installer that jpackage makes.

Usage: add-open-with.py <jpackage's main.wxs> <patched main.wxs>

Instead of taking over .log files (their icon, double-click and type name stay as they
are), the installer adds:
  - "Open with Logsift" to the right-click menu of files and folders (a folder opens a
    search in it)
  - Logsift to the "Open with" list of .log, .txt and .out files
Everything is under HKCU and is removed again on uninstall.
"""
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


def main(src, dst):
    s = open(src, encoding='utf-8').read()
    if '</Product>' not in s or '</Feature>' not in s or 'INSTALLDIR' not in s:
        sys.exit(f'{src}: not the main.wxs this script knows (no Product, Feature or INSTALLDIR)')
    s = s.replace('</Feature>', '  <ComponentRef Id="LogsiftOpenWith"/>\n    </Feature>', 1)
    s = s.replace('</Product>', COMPONENT + '  </Product>', 1)
    open(dst, 'w', encoding='utf-8').write(s)
    print(f'{dst}: added Open with Logsift')


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
