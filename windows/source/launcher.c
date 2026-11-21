/* 快捷证件照 Windows 版启动器（v2.2.0）
 * 无控制台；定位自身目录后启动便携 Python 运行时。
 *
 * v2.2.0 关键修复：
 *   旧版用 swprintf(L"...%s...", dir) 拼接路径。MinGW 在
 *   __USE_MINGW_ANSI_STDIO 下把 %s 当窄字符串处理，宽字符路径
 *   （含中文）会被截断成首字符（截图中的“程序目录：C”即此因）。
 *   本版所有宽字符串一律用 wcscpy/wcscat 手动拼接，彻底规避
 *   %s/%ls 的语义歧义；仅数字转换使用 swprintf（无字符串格式符，安全）。
 */
#include <windows.h>
#include <stdio.h>
#include <wchar.h>

/* 从完整路径中取出所在目录（去除尾部反斜杠） */
static void dirname_of(const wchar_t *path, wchar_t *out, size_t n) {
    size_t len = wcslen(path);
    if (len >= n) len = n - 1;
    wcsncpy(out, path, len);
    out[len] = L'\0';
    for (size_t i = len; i > 0; --i) {
        if (out[i - 1] == L'\\' || out[i - 1] == L'/') {
            out[i - 1] = L'\0';
            break;
        }
    }
}

static BOOL exists_w(const wchar_t *p) {
    return GetFileAttributesW(p) != INVALID_FILE_ATTRIBUTES;
}

/* 安全地在宽字符串缓冲区尾部追加 */
static void append(wchar_t *buf, size_t n, const wchar_t *s) {
    size_t k = wcslen(buf);
    if (k >= n - 1) return;
    size_t r = wcslen(s);
    if (r > n - 1 - k) r = n - 1 - k;
    wcsncpy(buf + k, s, r);
    buf[k + r] = L'\0';
}

/* 数字转宽字符串：仅数字格式符，不受 %s 宽窄语义影响 */
static void num2w(wchar_t *out, size_t n, unsigned long v) {
    swprintf(out, n, L"%lu", v);
}

/* 写诊断日志（传入已拼好的字符串，不做任何格式化） */
static void write_log(const wchar_t *dir, const wchar_t *text) {
    wchar_t path[1024];
    wcscpy(path, dir);
    append(path, 1024, L"\\启动诊断.log");
    FILE *f = _wfopen(path, L"a, ccs=UTF-8");
    if (!f) return;
    fputws(text, f);
    fputwc(L'\n', f);
    fclose(f);
}

static BOOL launch(wchar_t *dir, const wchar_t *py, const wchar_t *script,
                   wchar_t *pypath, wchar_t *pyhome, wchar_t *plugins, DWORD *errcode) {
    SetEnvironmentVariableW(L"PYTHONHOME", pyhome);
    SetEnvironmentVariableW(L"PYTHONPATH", pypath);
    SetEnvironmentVariableW(L"QT_PLUGIN_PATH", plugins);
    SetEnvironmentVariableW(L"PYTHONIOENCODING", L"utf-8");
    SetEnvironmentVariableW(L"PYTHONUTF8", L"1");

    wchar_t cmd[3072];
    wcscpy(cmd, L"\"");
    append(cmd, 3072, py);
    append(cmd, 3072, L"\" \"");
    append(cmd, 3072, script);
    append(cmd, 3072, L"\"");
    STARTUPINFOW si;
    PROCESS_INFORMATION pi;
    ZeroMemory(&si, sizeof(si));
    ZeroMemory(&pi, sizeof(pi));
    si.cb = sizeof(si);
    BOOL ok = CreateProcessW(NULL, cmd, NULL, NULL, FALSE, CREATE_NO_WINDOW,
                             NULL, dir, &si, &pi);
    if (!ok) {
        *errcode = GetLastError();
        return FALSE;
    }
    WaitForSingleObject(pi.hProcess, INFINITE);
    GetExitCodeProcess(pi.hProcess, errcode);
    CloseHandle(pi.hProcess);
    CloseHandle(pi.hThread);
    return TRUE;
}

int WINAPI wWinMain(HINSTANCE hInstance, HINSTANCE hPrev, PWSTR cmdline, int show) {
    (void)hInstance; (void)hPrev; (void)cmdline; (void)show;
    wchar_t exe[MAX_PATH];
    wchar_t dir[MAX_PATH];
    GetModuleFileNameW(NULL, exe, MAX_PATH);
    dirname_of(exe, dir, MAX_PATH);

    wchar_t pyw[1024], pye[1024], script[1024];
    wchar_t pypath[2048], pyhome[1024], plugins[1024];

    wcscpy(pyw, dir);    append(pyw, 1024, L"\\runtime\\python\\pythonw.exe");
    wcscpy(pye, dir);    append(pye, 1024, L"\\runtime\\python\\python.exe");
    wcscpy(script, dir); append(script, 1024, L"\\app\\main.py");
    wcscpy(pypath, dir); append(pypath, 2048, L"\\runtime\\lib;");
                         append(pypath, 2048, dir); append(pypath, 2048, L"\\app");
    wcscpy(pyhome, dir); append(pyhome, 1024, L"\\runtime\\python");
    wcscpy(plugins, dir);append(plugins, 1024, L"\\runtime\\lib\\PyQt5\\Qt5\\plugins");

    /* 1) 精确检查关键文件，列出真正缺失的项 */
    wchar_t missing[2048] = L"";
    int miss = 0;
    wchar_t num[32];
    if (!exists_w(pye))    { append(missing, 2048, L"  · runtime\\python\\python.exe\n"); miss++; }
    if (!exists_w(script)) { append(missing, 2048, L"  · app\\main.py\n"); miss++; }

    /* UCRT 关键 DLL（Win7 启动 python 必需） */
    wchar_t probe[1024];
    const wchar_t *crt[] = {
        L"python38.dll", L"VCRUNTIME140.dll", L"ucrtbase.dll",
        L"api-ms-win-crt-runtime-l1-1-0.dll", L"api-ms-win-crt-math-l1-1-0.dll",
        L"api-ms-win-crt-stdio-l1-1-0.dll", L"api-ms-win-crt-locale-l1-1-0.dll",
        L"api-ms-win-crt-heap-l1-1-0.dll"
    };
    for (int i = 0; i < 8; ++i) {
        wcscpy(probe, dir);
        append(probe, 1024, L"\\runtime\\python\\");
        append(probe, 1024, crt[i]);
        if (!exists_w(probe)) {
            append(missing, 2048, L"  · runtime\\python\\");
            append(missing, 2048, crt[i]);
            append(missing, 2048, L"\n");
            miss++;
        }
    }

    if (miss > 0) {
        wchar_t msg[3072] = L"";
        append(msg, 3072, L"启动失败：以下必需文件缺失（");
        num2w(num, 32, (unsigned long)miss); append(msg, 3072, num);
        append(msg, 3072, L" 项）：\n\n");
        append(msg, 3072, missing);
        append(msg, 3072, L"\n请重新完整解压安装包，确保所有文件都在同一目录。\n\n程序目录：");
        append(msg, 3072, dir);
        wchar_t log[3072] = L"[启动失败] 缺失 ";
        num2w(num, 32, (unsigned long)miss); append(log, 3072, num);
        append(log, 3072, L" 项:\n"); append(log, 3072, missing);
        append(log, 3072, L"程序目录："); append(log, 3072, dir);
        write_log(dir, log);
        MessageBoxW(NULL, msg, L"快捷证件照 · 启动失败", MB_ICONERROR | MB_OK);
        return 1;
    }

    /* 2) 尝试 pythonw.exe（无窗口），失败则回退 python.exe */
    DWORD code = 0;
    BOOL started = FALSE;
    if (exists_w(pyw)) {
        started = launch(dir, pyw, script, pypath, pyhome, plugins, &code);
    }
    if (!started) {
        DWORD firstErr = code;
        code = 0;
        started = launch(dir, pye, script, pypath, pyhome, plugins, &code);
        if (!started) {
            wchar_t msg[3072] = L"";
            append(msg, 3072, L"无法启动 Python 运行时。\n（pythonw.exe 错误码 ");
            num2w(num, 32, firstErr); append(msg, 3072, num);
            append(msg, 3072, L"，python.exe 错误码 ");
            num2w(num, 32, code);   append(msg, 3072, num);
            append(msg, 3072, L"）");

            wchar_t sysmsg[512];
            DWORD len = FormatMessageW(FORMAT_MESSAGE_FROM_SYSTEM | FORMAT_MESSAGE_IGNORE_INSERTS,
                                       NULL, code, 0, sysmsg, 512, NULL);
            if (len == 0) wcscpy(sysmsg, L"(无描述)");
            append(msg, 3072, L"\nWindows 错误码：");
            num2w(num, 32, code); append(msg, 3072, num);
            append(msg, 3072, L"\n系统说明："); append(msg, 3072, sysmsg);

            wchar_t log[1536] = L"[启动失败] CreateProcess 失败: pythonw=";
            num2w(num, 32, firstErr); append(log, 1536, num);
            append(log, 1536, L" python=");
            num2w(num, 32, code);     append(log, 1536, num);
            append(log, 1536, L"\n程序目录："); append(log, 1536, dir);
            write_log(dir, log);
            MessageBoxW(NULL, msg, L"快捷证件照 · 启动失败", MB_ICONERROR | MB_OK);
            return 1;
        }
    }

    /* 3) 进程正常退出但返回非 0：可能是 Python 层错误，写日志提示 */
    if (code != 0) {
        wchar_t msg[2048] = L"";
        append(msg, 2048, L"程序运行结束时返回错误码：");
        num2w(num, 32, code); append(msg, 2048, num);
        append(msg, 2048, L"\n\n详细信息请查看程序目录下的「启动诊断.log」。");

        wchar_t log[256] = L"[退出] 进程返回码=";
        num2w(num, 32, code); append(log, 256, num);
        write_log(dir, log);
        MessageBoxW(NULL, msg, L"快捷证件照 · 运行提示", MB_ICONWARNING | MB_OK);
    }
    return (int)code;
}
