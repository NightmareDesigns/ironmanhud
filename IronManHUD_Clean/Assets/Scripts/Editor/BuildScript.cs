using UnityEditor;
using UnityEditor.Build;
using UnityEditor.Build.Reporting;
using UnityEngine;
using System.Linq;

namespace StarkIndustries.Editor
{
    public class BuildScript
    {
        public static void BuildForDevice()
        {
            string deviceArg = GetCommandLineArg("device") ?? "auto";
            string buildPathArg = GetCommandLineArg("buildPath") ?? "Builds/IronManHUD.apk";

            DeviceProfile profile = ParseDevice(deviceArg);
            ApplyDeviceSettings(profile);

            var buildPlayerOptions = new BuildPlayerOptions
            {
                scenes = GetEnabledScenes(),
                locationPathName = buildPathArg,
                target = BuildTarget.Android,
                options = BuildOptions.Development | BuildOptions.AllowDebugging | BuildOptions.ConnectWithProfiler
            };

            BuildReport report = BuildPipeline.BuildPlayer(buildPlayerOptions);
            BuildSummary summary = report.summary;

            if (summary.result == BuildResult.Succeeded)
            {
                Debug.Log($"✅ Build succeeded: {buildPathArg} ({summary.totalSize / 1024 / 1024} MB)");
            }
            else
            {
                Debug.LogError($"❌ Build failed: {summary.result}");
                foreach (var step in report.steps)
                {
                    foreach (var msg in step.messages.Where(m => m.type == LogType.Error))
                        Debug.LogError(msg.content);
                }
                throw new System.Exception("Build failed");
            }
        }

        private static DeviceProfile ParseDevice(string arg)
        {
            return arg.ToLower() switch
            {
                "pixel" => DeviceProfile.Pixel10ProXL,
                "s24" => DeviceProfile.GalaxyS24,
                "s24ultra" => DeviceProfile.GalaxyS24Ultra,
                _ => DeviceProfile.GenericAndroid
            };
        }

        private static void ApplyDeviceSettings(DeviceProfile profile)
        {
            PlayerSettings.SetScriptingBackend(BuildTarget.Android, ScriptingImplementation.IL2CPP);
            PlayerSettings.SetApiCompatibilityLevel(BuildTarget.Android, ApiCompatibilityLevel.NET_Standard_2_1);
            PlayerSettings.Android.targetArchitectures = AndroidArchitecture.ARM64;
            PlayerSettings.Android.minSdkVersion = AndroidSdkVersions.AndroidApiLevel29;
            PlayerSettings.Android.targetSdkVersion = AndroidSdkVersions.AndroidApiLevel34;

            PlayerSettings.productName = "IronMan HUD";
            PlayerSettings.applicationIdentifier = "com.starkindustries.ironmanhud";
            PlayerSettings.bundleVersion = "1.0.0";
            PlayerSettings.Android.bundleVersionCode = 1;

            // Graphics
            PlayerSettings.SetGraphicsAPIs(BuildTarget.Android, new[] { UnityEngine.Rendering.GraphicsDeviceType.Vulkan, UnityEngine.Rendering.GraphicsDeviceType.OpenGLES3 });
            PlayerSettings.colorSpace = ColorSpace.Linear;
            PlayerSettings.MTRendering = true;

            // XR
            PlayerSettings.virtualRealitySupported = true;

            // Device-specific
            switch (profile)
            {
                case DeviceProfile.Pixel10ProXL:
                    QualitySettings.SetQualityLevel(2, true);
                    PlayerSettings.Android.targetArchitectures = AndroidArchitecture.ARM64;
                    break;
                case DeviceProfile.GalaxyS24Ultra:
                    QualitySettings.SetQualityLevel(2, true);
                    break;
                case DeviceProfile.GalaxyS24:
                    QualitySettings.SetQualityLevel(1, true);
                    break;
                default:
                    QualitySettings.SetQualityLevel(1, true);
                    break;
            }

            // Permissions
            PlayerSettings.Android.forceInternetPermission = true;
            PlayerSettings.Android.forceSDCardPermission = false;
        }

        private static string[] GetEnabledScenes()
        {
            return EditorBuildSettings.scenes
                .Where(s => s.enabled)
                .Select(s => s.path)
                .ToArray();
        }

        private static string GetCommandLineArg(string name)
        {
            var args = System.Environment.GetCommandLineArgs();
            for (int i = 0; i < args.Length - 1; i++)
            {
                if (args[i] == $"-{name}")
                    return args[i + 1];
            }
            return null;
        }
    }

    public enum DeviceProfile { Pixel10ProXL, GalaxyS24, GalaxyS24Ultra, GenericAndroid }
}