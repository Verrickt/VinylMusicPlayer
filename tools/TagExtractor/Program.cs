using System.Collections.Concurrent;
using System.Text.Encodings.Web;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace TagExtractor;

public class AudioMetadataItem
{
    public string FilePath { get; set; } = string.Empty;
    public string FileName { get; set; } = string.Empty;
    public string Extension { get; set; } = string.Empty;
    public long FileSizeBytes { get; set; }

    // Standard Tags
    public string? Title { get; set; }
    public string[] Artists { get; set; } = Array.Empty<string>();
    public string? Album { get; set; }
    public string[] AlbumArtists { get; set; } = Array.Empty<string>();
    public string[] Genres { get; set; } = Array.Empty<string>();
    public uint Year { get; set; }
    public uint TrackNumber { get; set; }
    public uint TrackCount { get; set; }
    public uint DiscNumber { get; set; }
    public double DurationSeconds { get; set; }

    // Audio format info
    public int Bitrate { get; set; }
    public int SampleRate { get; set; }
    public int Channels { get; set; }

    // ReplayGain Normalized Values (dB or ratio, null if not present)
    public double? ReplayGainTrackGain { get; set; }
    public double? ReplayGainTrackPeak { get; set; }
    public double? ReplayGainAlbumGain { get; set; }
    public double? ReplayGainAlbumPeak { get; set; }

    // Raw Tags extracted from container (TXXX, Vorbis comments, APE, Apple tags)
    public Dictionary<string, string> RawTags { get; set; } = new(StringComparer.OrdinalIgnoreCase);

    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? ReadError { get; set; }
}

public static class Program
{
    private static readonly HashSet<string> SupportedExtensions = new(StringComparer.OrdinalIgnoreCase)
    {
        ".mp3", ".flac", ".ogg", ".opus", ".m4a", ".aac", ".alac", ".wav", ".ape", ".wma"
    };

    public static async Task Main(string[] args)
    {
        Console.OutputEncoding = System.Text.Encoding.UTF8;

        string targetDir;
        string outputFile;

        if (args.Length > 0 && !string.IsNullOrWhiteSpace(args[0]))
        {
            targetDir = Path.GetFullPath(args[0]);
            outputFile = args.Length > 1 
                ? Path.GetFullPath(args[1]) 
                : Path.Combine(Directory.GetCurrentDirectory(), "extracted_tags.json");
        }
        else
        {
            Console.WriteLine("=== Audio Metadata & ReplayGain Tag Extractor ===");
            Console.Write("请输入音乐文件夹路径: ");
            targetDir = Console.ReadLine()?.Trim('\"', ' ') ?? "";
            outputFile = Path.Combine(Directory.GetCurrentDirectory(), "extracted_tags.json");
        }

        if (string.IsNullOrWhiteSpace(targetDir) || !Directory.Exists(targetDir))
        {
            Console.ForegroundColor = ConsoleColor.Red;
            Console.WriteLine($"[错误] 目录不存在: {targetDir}");
            Console.ResetColor();
            return;
        }

        Console.WriteLine($"[1/3] 正在扫描目录: {targetDir} ...");
        var files = Directory.EnumerateFiles(targetDir, "*.*", SearchOption.AllDirectories)
            .Where(f => SupportedExtensions.Contains(Path.GetExtension(f)))
            .ToList();

        Console.WriteLine($"找到支持的音频文件: {files.Count} 个");
        if (files.Count == 0) return;

        Console.WriteLine($"[2/3] 开始提取 Tag 与 ReplayGain (并发处理中)...");

        var results = new ConcurrentBag<AudioMetadataItem>();
        int processedCount = 0;
        int rgFoundCount = 0;
        int totalFiles = files.Count;

        var progressLock = new object();

        Parallel.ForEach(files, new ParallelOptions { MaxDegreeOfParallelism = Environment.ProcessorCount }, filePath =>
        {
            var item = ExtractFileMetadata(filePath);
            if (item.ReplayGainTrackGain.HasValue || item.ReplayGainAlbumGain.HasValue)
            {
                Interlocked.Increment(ref rgFoundCount);
            }
            results.Add(item);

            int current = Interlocked.Increment(ref processedCount);
            if (current % 50 == 0 || current == totalFiles)
            {
                lock (progressLock)
                {
                    Console.Write($"\r进度: [{current}/{totalFiles}] ({(current * 100.0 / totalFiles):F1}%) | 包含ReplayGain: {rgFoundCount} 首    ");
                }
            }
        });

        Console.WriteLine();
        Console.WriteLine($"[3/3] 正在保存结果至: {outputFile} ...");

        var sortedResults = results.OrderBy(x => x.FilePath).ToList();

        var jsonOptions = new JsonSerializerOptions
        {
            WriteIndented = true,
            Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping
        };

        var json = JsonSerializer.Serialize(sortedResults, jsonOptions);
        await File.WriteAllTextAsync(outputFile, json, System.Text.Encoding.UTF8);

        Console.ForegroundColor = ConsoleColor.Green;
        Console.WriteLine($"[完成] 成功提取 {sortedResults.Count} 个文件的标签数据！");
        Console.WriteLine($"包含 ReplayGain 信息的曲目数: {rgFoundCount}");
        Console.WriteLine($"JSON 文件已生成: {outputFile}");
        Console.ResetColor();
    }

    private static AudioMetadataItem ExtractFileMetadata(string filePath)
    {
        var fi = new FileInfo(filePath);
        var item = new AudioMetadataItem
        {
            FilePath = filePath,
            FileName = fi.Name,
            Extension = fi.Extension.ToLowerInvariant(),
            FileSizeBytes = fi.Exists ? fi.Length : 0
        };

        try
        {
            using var file = TagLib.File.Create(filePath);
            var tag = file.Tag;

            item.Title = tag.Title;
            item.Artists = tag.Performers ?? Array.Empty<string>();
            item.Album = tag.Album;
            item.AlbumArtists = tag.AlbumArtists ?? Array.Empty<string>();
            item.Genres = tag.Genres ?? Array.Empty<string>();
            item.Year = tag.Year;
            item.TrackNumber = tag.Track;
            item.TrackCount = tag.TrackCount;
            item.DiscNumber = tag.Disc;

            if (file.Properties != null)
            {
                item.DurationSeconds = file.Properties.Duration.TotalSeconds;
                item.Bitrate = file.Properties.AudioBitrate;
                item.SampleRate = file.Properties.AudioSampleRate;
                item.Channels = file.Properties.AudioChannels;
            }

            // 1. TagLib 内置解析的 ReplayGain
            if (!double.IsNaN(tag.ReplayGainTrackGain)) item.ReplayGainTrackGain = tag.ReplayGainTrackGain;
            if (!double.IsNaN(tag.ReplayGainTrackPeak)) item.ReplayGainTrackPeak = tag.ReplayGainTrackPeak;
            if (!double.IsNaN(tag.ReplayGainAlbumGain)) item.ReplayGainAlbumGain = tag.ReplayGainAlbumGain;
            if (!double.IsNaN(tag.ReplayGainAlbumPeak)) item.ReplayGainAlbumPeak = tag.ReplayGainAlbumPeak;

            // 2. 提取底层原始标签 (RawTags)，方便后续做单元测试对比
            ExtractRawTags(file, item.RawTags);

            // 3. 如果内置字段没读到，尝试从 RawTags 容错补全
            FallbackReplayGainFromRawTags(item);
        }
        catch (Exception ex)
        {
            item.ReadError = ex.Message;
        }

        return item;
    }

    private static void ExtractRawTags(TagLib.File file, Dictionary<string, string> rawTags)
    {
        // ID3v2 Tags (MP3 等)
        if (file.GetTag(TagLib.TagTypes.Id3v2) is TagLib.Id3v2.Tag id3v2)
        {
            foreach (var frame in id3v2.GetFrames<TagLib.Id3v2.UserTextInformationFrame>())
            {
                string desc = frame.Description ?? string.Empty;
                string val = string.Join("; ", frame.Text ?? Array.Empty<string>());
                rawTags[$"TXXX:{desc}"] = val;
            }
        }

        // Xiph / Vorbis Comments (FLAC, OGG, Opus)
        if (file.GetTag(TagLib.TagTypes.Xiph) is TagLib.Ogg.XiphComment xiph)
        {
            foreach (var field in xiph)
            {
                var values = xiph.GetField(field);
                rawTags[field] = values != null ? string.Join("; ", values) : string.Empty;
            }
        }

        // APE Tags (APE 或 MP3 尾部 APE 标签)
        if (file.GetTag(TagLib.TagTypes.Ape) is TagLib.Ape.Tag ape)
        {
            foreach (string key in ape)
            {
                try
                {
                    var apeItem = ape.GetItem(key);
                    rawTags[$"APE:{key}"] = apeItem != null ? string.Join("; ", apeItem.ToStringArray()) : string.Empty;
                }
                catch { }
            }
        }

        // Apple / MP4 Tags (M4A / AAC)
        if (file.GetTag(TagLib.TagTypes.Apple) is TagLib.Mpeg4.AppleTag apple)
        {
            string[] dashKeys =
            {
                "replaygain_track_gain", "replaygain_track_peak",
                "replaygain_album_gain", "replaygain_album_peak",
                "REPLAYGAIN_TRACK_GAIN", "REPLAYGAIN_TRACK_PEAK",
                "REPLAYGAIN_ALBUM_GAIN", "REPLAYGAIN_ALBUM_PEAK",
                "R128_TRACK_GAIN", "R128_ALBUM_GAIN"
            };
            foreach (var key in dashKeys)
            {
                try
                {
                    string? val = apple.GetDashBox("com.apple.iTunes", key);
                    if (!string.IsNullOrEmpty(val))
                    {
                        rawTags[$"iTunes:{key}"] = val;
                    }
                }
                catch { }
            }
        }
    }

    private static void FallbackReplayGainFromRawTags(AudioMetadataItem item)
    {
        foreach (var (key, value) in item.RawTags)
        {
            string upperKey = key.ToUpperInvariant();

            if (!item.ReplayGainTrackGain.HasValue && upperKey.Contains("REPLAYGAIN_TRACK_GAIN"))
            {
                if (TryParseDb(value, out double val)) item.ReplayGainTrackGain = val;
            }
            else if (!item.ReplayGainTrackPeak.HasValue && upperKey.Contains("REPLAYGAIN_TRACK_PEAK"))
            {
                if (double.TryParse(value, System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out double val))
                    item.ReplayGainTrackPeak = val;
            }
            else if (!item.ReplayGainAlbumGain.HasValue && upperKey.Contains("REPLAYGAIN_ALBUM_GAIN"))
            {
                if (TryParseDb(value, out double val)) item.ReplayGainAlbumGain = val;
            }
            else if (!item.ReplayGainAlbumPeak.HasValue && upperKey.Contains("REPLAYGAIN_ALBUM_PEAK"))
            {
                if (double.TryParse(value, System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out double val))
                    item.ReplayGainAlbumPeak = val;
            }
            // Opus R128 支持
            else if (!item.ReplayGainTrackGain.HasValue && upperKey.Contains("R128_TRACK_GAIN"))
            {
                if (int.TryParse(value, out int q8Val)) item.ReplayGainTrackGain = q8Val / 256.0;
            }
        }
    }

    private static bool TryParseDb(string text, out double db)
    {
        db = 0.0;
        if (string.IsNullOrWhiteSpace(text)) return false;

        var clean = new string(text.Where(c => char.IsDigit(c) || c == '.' || c == '-' || c == '+').ToArray());
        return double.TryParse(clean, System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out db);
    }
}
