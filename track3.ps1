$path = 'C:/Users/eric steph/Desktop/projects/edit/New folder (2)/AICreatorStudio/app/src/main/java/com/aicreatorstudio/app/ui/screens/editor/EditorScreen.kt'
$lines = Get-Content $path
$stack = New-Object System.Collections.Generic.List[int]
$inBlockComment = $false
$inLineComment = $false
$inString = $null
$inTripleString = $false

for ($i = 0; $i -lt $lines.Count; $i++) {
    $line = $lines[$i]
    $j = 0
    while ($j -lt $line.Length) {
        $c = $line[$j]
        $nxt = if ($j + 1 -lt $line.Length) { $line[$j + 1] } else { '' }
        if ($inBlockComment) {
            if ($c -eq '*' -and $nxt -eq '/') { $inBlockComment = $false; $j += 2; continue }
            $j++; continue
        }
        if ($inLineComment) { break }
        if ($inTripleString) {
            if ($c -eq '"' -and $nxt -eq '"' -and ($j + 2 -lt $line.Length) -and $line[$j + 2] -eq '"') {
                $inTripleString = $false; $j += 3; continue
            }
            $j++; continue
        }
        if ($inString -ne $null) {
            if ($c -eq '\') { $j += 2; continue }
            if ($c -eq $inString) { $inString = $null; $j++; continue }
            $j++; continue
        }
        if ($c -eq '/' -and $nxt -eq '/') { $inLineComment = $true; break }
        if ($c -eq '/' -and $nxt -eq '*') { $inBlockComment = $true; $j += 2; continue }
        if ($c -eq '"' -and $nxt -eq '"' -and ($j + 2 -lt $line.Length) -and $line[$j + 2] -eq '"') {
            $inTripleString = $true; $j += 3; continue
        }
        if ($c -eq '"' -or $c -eq "'") { $inString = $c; $j++; continue }
        if ($c -eq '{') {
            $stack.Add($i+1)
        } elseif ($c -eq '}') {
            if ($stack.Count -gt 0) {
                $stack.RemoveAt($stack.Count - 1)
            } else {
                Write-Host ("Unexpected } at line {0}" -f ($i + 1))
            }
        }
        $j++
    }
    $inLineComment = $false
}

if ($stack.Count -gt 0) {
    Write-Host ("Unclosed { remaining: " + $stack.Count)
    foreach ($lineNum in $stack) {
        $lineText = $lines[$lineNum - 1].TrimEnd()
        if ($lineText.Length -gt 80) { $lineText = $lineText.Substring(0, 80) + "..." }
        Write-Host ("  Line {0}: {1}" -f $lineNum, $lineText)
    }
}
