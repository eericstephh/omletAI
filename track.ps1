$path = 'C:/Users/eric steph/Desktop/projects/edit/New folder (2)/AICreatorStudio/app/src/main/java/com/aicreatorstudio/app/ui/screens/editor/EditorScreen.kt'
$content = Get-Content $path -Raw
# Strip block comments (non-greedy)
$content = [regex]::Replace($content, '/\*[\s\S]*?\*/', '')
# Strip line comments
$content = [regex]::Replace($content, '//[^\r\n]*', '')
# Strip strings - keep them simple
$content = [regex]::Replace($content, '"(?:\\.|[^"\\\r\n])*"', '""')
$content = [regex]::Replace($content, "'(?:\\.|[^'\\\r\n])*'", "''")
$content = [regex]::Replace($content, '"""[\s\S]*?"""', '""""""')

$open = ([regex]::Matches($content, '\{')).Count
$close = ([regex]::Matches($content, '\}')).Count
Write-Host ("Open=" + $open + " Close=" + $close + " Diff=" + ($open - $close))
