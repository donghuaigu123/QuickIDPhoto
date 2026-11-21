/* 快捷证件照 Windows 版启动器（v1.9.2）
 * 无控制台；定位自身目录后启动便携 Python 运行时。
 * 失败时给出精确诊断：缺失文件清单 + Windows 错误码 + 日志文件。
 */
#include <windows.h>
#include <stdio.h>
#include <stdarg.h>

/* 从完整路径中取出所在目录（含尾部反斜杠） */
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

/* 写诊断日志到 exe 同目录 */
static void write_log(const wchar_t *dir, const wchar_t *fmt, ...) {
    wchar_t path[1024];
    swprintf(path, 1024, L"%s\\启动诊断.log", dir);
    FILE *f = _wfopen(path, L"a, ccs=UTF-8");
    if (!f) return;
    va_list ap;
    va_start(ap, fmt);
    vfwprintf(f, fmt, ap);
    va_end(ap);
    fputwc(L'\n', f);
    fclose(f);
}

/* 把系统错误码转成可读文本 */
static void append_error(wchar_t *buf, size_t n, DWORD err) {
    wchar_t msg[512];
    DWORD len = FormatMessageW(FORMAT_MESSAGE_FROM_SYSTEM | FORMAT_MESSAGE_IGNORE_INSERTS,
                               NULL, err, 0, msg, 512, NULL);
    if (len == 0) swprintf(msg, 512, L"(无描述)");
    size_t k = wcslen(buf);
    swprintf(buf + k, n - k, L"\nWindows 错误码：%lu\n系统说明：%s", err, msg);
}

static BOOL launch(wchar_t *dir, const wchar_t *py, const wchar_t *script,
                   wchar_t *pypath, wchar_t *pyhome, wchar_t *plugins, DWORD *errcode) {
    SetEnvironmentVariableW(L"PYTHONHOME", pyhome);
    SetEnvironmentVariableW(L"PYTHONPATH", pypath);
    SetEnvironmentVariableW(L"QT_PLUGIN_PATH", plugins);
    SetEnvironmentVariableW(L"PYTHONIOENCODING", L"utf-8");
    SetEnvironmentVariableW(L"PYTHONUTF8", L"1");

    wchar_t cmd[3072];
    swprintf(cmd, 3072, L"\"%s\" \"%s\"", py, script);
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
    swprintf(pyw, 1024, L"%s\\runtime\\python\\pythonw.exe", dir);
    swprintf(pye, 1024, L"%s\\runtime\\python\\python.exe", dir);
    swprintf(script, 1024, L"%s\\app\\main.py", dir);
    swprintf(pypath, 2048, L"%s\\runtime\\lib;%s\\app", dir, dir);
    swprintf(pyhome, 1024, L"%s\\runtime\\python", dir);
    swprintf(plugins, 1024, L"%s\\runtime\\lib\\PyQt5\\Qt5\\plugins", dir);

    /* 1) 精确检查关键文件，列出真正缺失的项 */
    wchar_t missing[2048] = L"";
    int miss = 0;
    if (!exists_w(pye))  { wcscat(missing, L"  · runtime\\python\\python.exe\n"); miss++; }
    if (!exists_w(script)){ wcscat(missing, L"  · app\\main.py\n"); miss++; }

    /* UCRT 关键 DLL（Win7 启动 python 必需） */
    wchar_t probe[1024];
    const wchar_t *crt[] = {
        L"python38.dll", L"VCRUNTIME140.dll", L"ucrtbase.dll",
        L"api-ms-win-crt-runtime-l1-1-0.dll", L"api-ms-win-crt-math-l1-1-0.dll",
        L"api-ms-win-crt-stdio-l1-1-0.dll", L"api-ms-win-crt-locale-l1-1-0.dll",
        L"api-ms-win-crt-heap-l1-1-0.dll"
    };
    for (int i = 0; i < 8; ++i) {
        swprintf(probe, 1024, L"%s\\runtime\\python\\%s", dir, crt[i]);
        if (!exists_w(probe)) {
            wcscat(missing, L"  · runtime\\python\\");
            wcscat(missing, crt[i]);
            wcscat(missing, L"\n");
            miss++;
        }
    }

    if (miss > 0) {
        wchar_t msg[3072];
        swprintf(msg, 3072,
                 L"启动失败：以下必需文件缺失（%d 项）：\n\n%s\n"
                 L"请重新完整解压安装包，确保所有文件都在同一目录。\n\n"
                 L"程序目录：%s", miss, missing, dir);
        MessageBoxW(NULL, msg, L"快捷证件照 · 启动失败", MB_ICONERROR | MB_OK);
        write_log(dir, L"[启动失败] 缺失 %d 项:\n%s", miss, missing);
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
            /* 两次都失败：显示真实的系统错误码 */
            wchar_t msg[3072];
            swprintf(msg, 3072,
                     L"无法启动 Python 运行时。\n（pythonw.exe 错误码 %lu，python.exe 错误码 %lu）",
                     firstErr, code);
            append_error(msg, 3072, code);
            MessageBoxW(NULL, msg, L"快捷证件照 · 启动失败", MB_ICONERROR | MB_OK);
            write_log(dir, L"[启动失败] CreateProcess 失败: pythonw=%lu python=%lu", firstErr, code);
            return 1;
        }
    }

    /* 3) 进程正常退出但返回非 0：可能是 Python 层错误，写日志提示 */
    if (code != 0) {
        wchar_t msg[2048];
        swprintf(msg, 2048,
                 L"程序运行结束时返回错误码：%lu\n\n"
                 L"详细信息请查看程序目录下的「启动诊断.log」。", code);
        write_log(dir, L"[退出] 进程返回码=%lu", code);
        MessageBoxW(NULL, msg, L"快捷证件照 · 运行提示", MB_ICONWARNING | MB_OK);
    }
    return (int)code;
}
