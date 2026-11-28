import React from 'react';

/**
 * Fixed bottom dock for the modpack downloader UI.
 * Displays an icon stack, file summary, primary actions, and a progress bar
 * that becomes visible while downloads are in progress.
 */
const BottomDock = ({
  downloading = false,
  progress = 0,
  currentFile = '',
  totalFiles = 0,
  onCancelDownload,
  onRetryDownload,
}) => {
  return (
    <div className="fixed bottom-0 left-0 right-0 bg-gray-800 text-white p-4 flex items-center justify-between gap-4 z-50 shadow-xl">
      <div className="flex items-center gap-4 min-w-0">
        {/* Icon stack: downloaded / failed / queued */}
        <div className="icon-stack flex items-center gap-2 bg-gray-900 p-2 rounded-lg border border-gray-700">
          <span className="text-gray-400 text-xs">📦</span>
          <span className="text-gray-300 text-xs font-mono">
            {totalFiles} file{totalFiles !== 1 ? 's' : ''}
          </span>
        </div>

        {/* File summary */}
        <div className="file-summary flex-1 min-w-0">
          <div className="text-xs text-gray-400 uppercase tracking-wider mb-1">
            Current File
          </div>
          <div className="truncate text-sm text-white font-medium">
            {currentFile || 'No file selected'}
          </div>
          <div className="text-xs text-gray-500 mt-1">
            {downloading ? 'Downloading...' : 'Idle'}
          </div>
        </div>
      </div>

      <div className="flex items-center gap-4">
        {/* Primary actions */}
        <div className="primary-actions flex items-center gap-2">
          {downloading ? (
            <button
              className="bg-gray-600 hover:bg-gray-500 text-white px-3 py-1.5 rounded text-xs font-semibold transition-colors"
              disabled={!downloading}
              onClick={onCancelDownload}
            >
              Cancel
            </button>
          ) : (
            <>
              <button
                className="bg-indigo-600 hover:bg-indigo-500 text-white px-3 py-1.5 rounded text-xs font-semibold transition-colors"
                disabled={downloading}
                onClick={onRetryDownload}
              >
                Retry
              </button>
              <button
                className="bg-gray-600 hover:bg-gray-500 text-white px-3 py-1.5 rounded text-xs font-semibold transition-colors"
                disabled={downloading}
              >
                Open Folder
              </button>
            </>
          )}
        </div>

        {/* Progress bar (visible during downloads) */}
        <div className="progress-bar w-48 hidden sm:flex flex-col gap-1">
          <div className="flex justify-between text-[10px] text-gray-400 mb-1">
            <span>Progress</span>
            <span>{progress}%</span>
          </div>
          <div className="w-full bg-gray-700 h-2 rounded-full overflow-hidden">
            <div
              className="bg-indigo-500 h-full transition-all duration-300"
              style={{ width: `${Math.min(Math.max(progress, 0), 100)}%` }}
              role="progressbar"
              aria-valuenow={progress}
              aria-valuemin={0}
              aria-valuemax={100}
            />
          </div>
        </div>
      </div>
    </div>
  );
};

export default BottomDock;
