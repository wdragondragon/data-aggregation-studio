param(
    [string]$BaseUri = "http://127.0.0.1:18080/api/v1",
    [string]$Username = "admin",
    [string]$Password = "admin123",
    [string]$TenantId = "default",
    [string]$ProjectId = "2041806351881003009",
    [switch]$DryRun
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function ConvertTo-CompactJson {
    param([Parameter(Mandatory = $true)]$Value)
    return ($Value | ConvertTo-Json -Depth 10 -Compress)
}

function Invoke-StudioApi {
    param(
        [Parameter(Mandatory = $true)][string]$Method,
        [Parameter(Mandatory = $true)][string]$Uri,
        [Parameter()][hashtable]$Headers,
        [Parameter()]$Body
    )

    $requestParams = @{
        Method = $Method
        Uri = $Uri
    }
    if ($Headers) {
        $requestParams.Headers = $Headers
    }
    if ($null -ne $Body) {
        $requestParams.ContentType = "application/json; charset=utf-8"
        $json = ($Body | ConvertTo-Json -Depth 20)
        $requestParams.Body = [System.Text.Encoding]::UTF8.GetBytes($json)
    }

    $response = Invoke-RestMethod @requestParams
    if (-not $response.success) {
        throw "Request failed: $($response.message)"
    }
    return $response.data
}

function Get-RepoRoot {
    $studioRoot = Split-Path $PSScriptRoot -Parent
    return Split-Path $studioRoot -Parent
}

function Parse-TransformerList {
    param([Parameter(Mandatory = $true)][string]$TransformerTxtPath)

    $currentType = $null
    $entries = New-Object System.Collections.Generic.List[object]
    foreach ($rawLine in Get-Content -Path $TransformerTxtPath -Encoding UTF8) {
        $line = $rawLine.Trim()
        if ([string]::IsNullOrWhiteSpace($line)) {
            continue
        }
        if ($line -notmatch '^(\S+)\s+(.*)$') {
            $currentType = $line
            continue
        }
        if (-not $currentType) {
            throw "Transformer type header is missing before line: $line"
        }
        $entries.Add([PSCustomObject]@{
                MappingCode = $matches[1].Trim()
                MappingName = $matches[2].Trim()
                MappingType = $currentType
            })
    }
    return $entries
}

function Resolve-TransformerJavaFile {
    param(
        [Parameter(Mandatory = $true)][string]$TransformerRoot,
        [Parameter(Mandatory = $true)][string]$MappingCode
    )

    $pluginDir = Join-Path $TransformerRoot $MappingCode
    if (-not (Test-Path $pluginDir)) {
        throw "Transformer plugin directory not found: $pluginDir"
    }
    $javaRoot = Join-Path $pluginDir "src\\main\\java"
    $transformerJsonPath = Join-Path $pluginDir "src\\main\\resources\\transformer.json"
    if (Test-Path $transformerJsonPath) {
        $transformerJson = Get-Content -Path $transformerJsonPath -Raw -Encoding UTF8 | ConvertFrom-Json
        if ($transformerJson.class) {
            $className = ($transformerJson.class -split '\.')[-1]
            $exactFile = Get-ChildItem -Path $javaRoot -Recurse -Filter "$className.java" | Select-Object -First 1
            if ($exactFile) {
                return $exactFile.FullName
            }
        }
    }

    $javaFiles = Get-ChildItem -Path $javaRoot -Recurse -Filter *.java | Sort-Object FullName
    $javaFile = $javaFiles |
        Where-Object { $_.DirectoryName -like "*\\impl" } |
        Select-Object -First 1
    if (-not $javaFile) {
        $javaFile = $javaFiles | Select-Object -First 1
    }
    if (-not $javaFile) {
        throw "Transformer source file not found for: $MappingCode"
    }
    return $javaFile.FullName
}

function Parse-TransformerParametersFromSource {
    param([Parameter(Mandatory = $true)][string]$JavaFile)

    $paramsByIndex = @{}
    foreach ($line in Get-Content -Path $JavaFile -Encoding UTF8) {
        if ($line -match '(?<var>[A-Za-z_][A-Za-z0-9_]*)\s*=\s*.*?paras\[(?<idx>\d+)\]') {
            $index = [int]$matches['idx']
            if ($index -le 0) {
                continue
            }
            if (-not $paramsByIndex.ContainsKey($index)) {
                $paramsByIndex[$index] = [PSCustomObject]@{
                    SourceIndex = $index
                    ParamName = $matches['var']
                    SourceLine = $line.Trim()
                }
            }
        }
    }

    $ordered = $paramsByIndex.GetEnumerator() | Sort-Object Key | ForEach-Object { $_.Value }
    $result = New-Object System.Collections.Generic.List[object]
    $order = 1
    foreach ($param in $ordered) {
        $result.Add([PSCustomObject]@{
                ParamName = $param.ParamName
                ParamOrder = $order
                SourceIndex = $param.SourceIndex
                SourceLine = $param.SourceLine
            })
        $order++
    }
    return $result
}

function New-OptionJson {
    param([Parameter(Mandatory = $true)][object[]]$Options)
    $normalized = @()
    foreach ($option in $Options) {
        if ($null -eq $option) {
            continue
        }
        $text = [string]$option
        if ([string]::IsNullOrWhiteSpace($text)) {
            continue
        }
        if ($normalized -notcontains $text) {
            $normalized += $text
        }
    }
    return ConvertTo-CompactJson -Value $normalized
}

function Resolve-ParameterUiMeta {
    param(
        [Parameter(Mandatory = $true)][string]$MappingCode,
        [Parameter(Mandatory = $true)][string]$ParamName
    )

    $key = "$MappingCode`:$ParamName"
    switch ($key) {
        "range_number_filter:saveOrDelete" {
            return @{
                ComponentType = "select"
                ParamValueJson = (New-OptionJson @(
                        "save",
                        "delete"
                    ))
                Description = "Keep or delete records matched in range"
            }
        }
        "range_number_filter:startValue" { return @{ ComponentType = "numberPicker"; ParamValueJson = ""; Description = "Inclusive range start value" } }
        "range_number_filter:endValue" { return @{ ComponentType = "numberPicker"; ParamValueJson = ""; Description = "Inclusive range end value" } }
        "string_operation_filter:saveOrDelete" {
            return @{
                ComponentType = "select"
                ParamValueJson = (New-OptionJson @(
                        "save",
                        "delete"
                    ))
                Description = "Keep or delete matched records"
            }
        }
        "string_operation_filter:operation" {
            return @{
                ComponentType = "select"
                ParamValueJson = (New-OptionJson @(
                        "Yes",
                        "No"
                    ))
                Description = "Enum matching strategy"
            }
        }
        "string_operation_filter:value" { return @{ ComponentType = "textArea"; ParamValueJson = ""; Description = "Comma separated enum values" } }
        "number_operation_filter:operation" {
            return @{
                ComponentType = "select"
                ParamValueJson = (New-OptionJson @(
                        ">",
                        "<",
                        "=",
                        "!=",
                        ">=",
                        "<="
                    ))
                Description = "Numeric comparison operator"
            }
        }
        "number_operation_filter:value" { return @{ ComponentType = "numberPicker"; ParamValueJson = ""; Description = "Comparison value" } }
        "date_operation_filter:saveOrDelete" {
            return @{
                ComponentType = "select"
                ParamValueJson = (New-OptionJson @(
                        "save",
                        "delete"
                    ))
                Description = "Keep or delete matched date range records"
            }
        }
        "date_operation_filter:valueDf" { return @{ ComponentType = "input"; ParamValueJson = ""; Description = "Date format like yyyy-MM-dd HH:mm:ss" } }
        "date_operation_filter:startValue" { return @{ ComponentType = "input"; ParamValueJson = ""; Description = "Start date string in the same format" } }
        "date_operation_filter:endValue" { return @{ ComponentType = "input"; ParamValueJson = ""; Description = "End date string in the same format" } }
        "date_filter:dateFormat" { return @{ ComponentType = "input"; ParamValueJson = ""; Description = "Input date format like yyyy-MM-dd HH:mm:ss" } }
        "null_value_replace:replaceValue" { return @{ ComponentType = "input"; ParamValueJson = ""; Description = "Replacement value for blank input" } }
        "add_default_value:defaultValue" { return @{ ComponentType = "input"; ParamValueJson = ""; Description = "Default value written to target column" } }
        "insert_sys_time:format" { return @{ ComponentType = "input"; ParamValueJson = ""; Description = "System time output format like yyyy-MM-dd HH:mm:ss" } }
        "date_transformer:dateFormatOld" { return @{ ComponentType = "input"; ParamValueJson = ""; Description = "Source date format, or stamp for timestamp" } }
        "date_transformer:dateFormatNew" { return @{ ComponentType = "input"; ParamValueJson = ""; Description = "Target date format, or stamp for timestamp" } }
        "value_filter:stringFormat" { return @{ ComponentType = "input"; ParamValueJson = ""; Description = "Source string date format, supports timestamp" } }
        "value_filter:toDate" {
            return @{
                ComponentType = "select"
                ParamValueJson = (New-OptionJson @(
                        "DATE",
                        "DATETIME",
                        "TIME"
                    ))
                Description = "Target date type"
            }
        }
        "replace_str:regex" { return @{ ComponentType = "input"; ParamValueJson = ""; Description = "Characters or regex patterns, comma separated" } }
        "replace_str:replaceMent" { return @{ ComponentType = "input"; ParamValueJson = ""; Description = "Replacement text" } }
        "number_cut:num" { return @{ ComponentType = "numberPicker"; ParamValueJson = ""; Description = "Decimal places to keep" } }
        "string_cut:saveOrDelete" {
            return @{
                ComponentType = "select"
                ParamValueJson = (New-OptionJson @(
                        "save",
                        "delete"
                    ))
                Description = "String cut strategy"
            }
        }
        "string_cut:beforeNum" { return @{ ComponentType = "numberPicker"; ParamValueJson = ""; Description = "Head length to save or cut" } }
        "string_cut:afterNum" { return @{ ComponentType = "numberPicker"; ParamValueJson = ""; Description = "Tail length to save or cut" } }
        "date_mask:hideOrShow" {
            return @{
                ComponentType = "select"
                ParamValueJson = (New-OptionJson @(
                        "hide",
                        "show"
                    ))
                Description = "Masking strategy"
            }
        }
        "date_mask:beforeNum" { return @{ ComponentType = "numberPicker"; ParamValueJson = ""; Description = "Head segment length" } }
        "date_mask:centerNum" { return @{ ComponentType = "numberPicker"; ParamValueJson = ""; Description = "Center segment length" } }
        "date_mask:afterNum" { return @{ ComponentType = "numberPicker"; ParamValueJson = ""; Description = "Tail segment length" } }
        "SHA256_str:key" { return @{ ComponentType = "input"; ParamValueJson = ""; Description = "Optional salt value" } }
        "MD5_str:key" { return @{ ComponentType = "input"; ParamValueJson = ""; Description = "Optional salt value" } }
        "sm2_str:key" { return @{ ComponentType = "textArea"; ParamValueJson = ""; Description = "Base64 key; public key for encrypt, private key for decrypt" } }
        "sm2_str:option" {
            return @{
                ComponentType = "select"
                ParamValueJson = (New-OptionJson @(
                        "encrypt",
                        "decrypt"
                    ))
                Description = "SM2 operation"
            }
        }
        "rsa_str:key" { return @{ ComponentType = "textArea"; ParamValueJson = ""; Description = "Base64 key; public key for encrypt, private key for decrypt" } }
        "rsa_str:option" {
            return @{
                ComponentType = "select"
                ParamValueJson = (New-OptionJson @(
                        "encrypt",
                        "decrypt"
                    ))
                Description = "RSA operation"
            }
        }
        "idea_str:key" { return @{ ComponentType = "input"; ParamValueJson = ""; Description = "Base64 encoded 16-byte IDEA key" } }
        "idea_str:option" {
            return @{
                ComponentType = "select"
                ParamValueJson = (New-OptionJson @(
                        "encrypt",
                        "decrypt"
                    ))
                Description = "IDEA operation"
            }
        }
        "3des_str:key" { return @{ ComponentType = "input"; ParamValueJson = ""; Description = "Base64 encoded 24-byte 3DES key" } }
        "3des_str:option" {
            return @{
                ComponentType = "select"
                ParamValueJson = (New-OptionJson @(
                        "encrypt",
                        "decrypt"
                    ))
                Description = "3DES operation"
            }
        }
        "des_str:key" { return @{ ComponentType = "input"; ParamValueJson = ""; Description = "Base64 encoded 8-byte DES key" } }
        "des_str:option" {
            return @{
                ComponentType = "select"
                ParamValueJson = (New-OptionJson @(
                        "encrypt",
                        "decrypt"
                    ))
                Description = "DES operation"
            }
        }
        "aes_str:key" { return @{ ComponentType = "input"; ParamValueJson = ""; Description = "Base64 encoded 16-byte AES key" } }
        "aes_str:option" {
            return @{
                ComponentType = "select"
                ParamValueJson = (New-OptionJson @(
                        "encrypt",
                        "decrypt"
                    ))
                Description = "AES operation"
            }
        }
        default {
            if ($ParamName -match '^(beforeNum|afterNum|centerNum|num)$') {
                return @{ ComponentType = "numberPicker"; ParamValueJson = ""; Description = "" }
            }
            return @{ ComponentType = "input"; ParamValueJson = ""; Description = "" }
        }
    }
}

function Build-RulePayload {
    param(
        [Parameter(Mandatory = $true)]$Entry,
        [Parameter(Mandatory = $true)][string]$JavaFile,
        [Parameter()][Nullable[long]]$ExistingId
    )

    $params = New-Object System.Collections.Generic.List[object]
    $parsedParams = Parse-TransformerParametersFromSource -JavaFile $JavaFile
    foreach ($parsedParam in $parsedParams) {
        $uiMeta = Resolve-ParameterUiMeta -MappingCode $Entry.MappingCode -ParamName $parsedParam.ParamName
        $params.Add([ordered]@{
                paramName = $parsedParam.ParamName
                paramOrder = $parsedParam.ParamOrder
                componentType = $uiMeta.ComponentType
                paramValueJson = $uiMeta.ParamValueJson
                description = $uiMeta.Description
            })
    }

    $payload = [ordered]@{
        mappingName = $Entry.MappingName
        mappingType = $Entry.MappingType
        mappingCode = $Entry.MappingCode
        enabled = $true
        description = "Registered from transformer.txt and source: $(Split-Path $JavaFile -Leaf)"
        params = $params
    }
    if ($ExistingId) {
        $payload.id = [long]$ExistingId
    }
    return $payload
}

$repoRoot = Get-RepoRoot
$transformerRoot = Join-Path $repoRoot "job-plugins\\transformer"
$transformerTxtPath = Join-Path $transformerRoot "transformer.txt"

if (-not (Test-Path $transformerTxtPath)) {
    throw "transformer.txt not found: $transformerTxtPath"
}

$entries = Parse-TransformerList -TransformerTxtPath $transformerTxtPath

$loginData = Invoke-StudioApi -Method "POST" -Uri "$BaseUri/auth/login" -Body @{
    username = $Username
    password = $Password
}

$headers = @{
    Authorization = "Bearer $($loginData.token)"
    "X-Tenant-Id" = $TenantId
    "X-Project-Id" = $ProjectId
}

$existingPage = Invoke-StudioApi -Method "GET" -Uri "$BaseUri/field-mapping-rules?pageNo=1&pageSize=500" -Headers $headers
$existingByCode = @{}
foreach ($item in $existingPage.items) {
    $existingByCode[$item.mappingCode] = $item
}

$results = New-Object System.Collections.Generic.List[object]
foreach ($entry in $entries) {
    $javaFile = Resolve-TransformerJavaFile -TransformerRoot $transformerRoot -MappingCode $entry.MappingCode
    $existing = $existingByCode[$entry.MappingCode]
    $payload = Build-RulePayload -Entry $entry -JavaFile $javaFile -ExistingId $(if ($existing) { [long]$existing.id } else { $null })

    if ($DryRun) {
        $results.Add([PSCustomObject]@{
                action = $(if ($existing) { "update" } else { "create" })
                mappingCode = $entry.MappingCode
                mappingType = $entry.MappingType
                mappingName = $entry.MappingName
                paramCount = $payload.params.Count
                params = $payload.params
            })
        continue
    }

    $saved = Invoke-StudioApi -Method "POST" -Uri "$BaseUri/field-mapping-rules" -Headers $headers -Body $payload
    $results.Add([PSCustomObject]@{
            action = $(if ($existing) { "updated" } else { "created" })
            id = $saved.id
            mappingCode = $saved.mappingCode
            mappingType = $saved.mappingType
            mappingName = $saved.mappingName
            paramCount = @($saved.params).Count
        })
}

$results | Sort-Object mappingType, mappingCode | Format-Table -AutoSize

if (-not $DryRun) {
    $finalPage = Invoke-StudioApi -Method "GET" -Uri "$BaseUri/field-mapping-rules?pageNo=1&pageSize=500" -Headers $headers
    Write-Host ""
    Write-Host "Registered field mapping rules:" $finalPage.total
    $finalPage.items |
        Group-Object mappingType |
        Sort-Object Name |
        ForEach-Object { Write-Host ("  {0}: {1}" -f $_.Name, $_.Count) }
}
