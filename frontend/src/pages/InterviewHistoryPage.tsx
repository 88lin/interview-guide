import {useEffect, useState} from 'react';
import {motion} from 'framer-motion';
import {historyApi, InterviewItem} from '../api/history';
import {formatDateOnly} from '../utils/date';
import DeleteConfirmDialog from '../components/DeleteConfirmDialog';

interface InterviewHistoryPageProps {
  onBack: () => void;
  onViewInterview: (sessionId: string, resumeId?: number) => void;
}

interface InterviewWithResume extends InterviewItem {
  resumeId: number;
  resumeFilename: string;
}

export default function InterviewHistoryPage({ onBack, onViewInterview }: InterviewHistoryPageProps) {
  const [interviews, setInterviews] = useState<InterviewWithResume[]>([]);
  const [loading, setLoading] = useState(true);
  const [deletingSessionId, setDeletingSessionId] = useState<string | null>(null);
  const [deleteConfirm, setDeleteConfirm] = useState<{ sessionId: string } | null>(null);
  const [exporting, setExporting] = useState<string | null>(null);

  useEffect(() => {
    loadAllInterviews();
  }, []);

  const loadAllInterviews = async () => {
    setLoading(true);
    try {
      // 获取所有简历，然后聚合所有面试记录
      const resumes = await historyApi.getResumes();
      const allInterviews: InterviewWithResume[] = [];
      
      for (const resume of resumes) {
        const detail = await historyApi.getResumeDetail(resume.id);
        if (detail.interviews && detail.interviews.length > 0) {
          detail.interviews.forEach(interview => {
            allInterviews.push({ 
              ...interview, 
              resumeId: resume.id,
              resumeFilename: resume.filename 
            });
          });
        }
      }
      
      // 按创建时间倒序排序
      allInterviews.sort((a, b) => 
        new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime()
      );
      
      setInterviews(allInterviews);
    } catch (err) {
      console.error('加载面试记录失败', err);
    } finally {
      setLoading(false);
    }
  };

  const handleDeleteClick = (sessionId: string, e: React.MouseEvent) => {
    e.stopPropagation();
    setDeleteConfirm({ sessionId });
  };

  const handleDeleteConfirm = async () => {
    if (!deleteConfirm) return;
    
    const { sessionId } = deleteConfirm;
    setDeletingSessionId(sessionId);
    try {
      await historyApi.deleteInterview(sessionId);
      await loadAllInterviews();
      setDeleteConfirm(null);
    } catch (err) {
      alert(err instanceof Error ? err.message : '删除失败，请稍后重试');
    } finally {
      setDeletingSessionId(null);
    }
  };

  const handleExport = async (sessionId: string) => {
    setExporting(sessionId);
    try {
      const blob = await historyApi.exportInterviewPdf(sessionId);
      const url = window.URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `面试报告_${sessionId}.pdf`;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      window.URL.revokeObjectURL(url);
    } catch (err) {
      alert('导出失败，请重试');
    } finally {
      setExporting(null);
    }
  };

  if (loading) {
    return (
      <div className="flex items-center justify-center min-h-screen">
        <div className="text-center">
          <div className="w-10 h-10 border-3 border-slate-200 border-t-primary-500 rounded-full mx-auto mb-4 animate-spin" />
          <p className="text-slate-500">加载中...</p>
        </div>
      </div>
    );
  }

  return (
    <motion.div
      className="w-full"
      initial={{ opacity: 0 }}
      animate={{ opacity: 1 }}
    >
      {/* 头部 */}
      <div className="flex justify-between items-start mb-10 flex-wrap gap-6">
        <div>
          <motion.h1
            className="text-4xl font-bold text-slate-900 mb-2"
            initial={{ opacity: 0, x: -20 }}
            animate={{ opacity: 1, x: 0 }}
          >
            面试记录
          </motion.h1>
          <motion.p
            className="text-slate-500"
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            transition={{ delay: 0.1 }}
          >
            查看和管理所有模拟面试记录
          </motion.p>
        </div>
      </div>

      {/* 面试记录列表 */}
      {interviews.length === 0 ? (
        <motion.div
          className="bg-white rounded-2xl p-12 text-center"
          initial={{ opacity: 0, y: 20 }}
          animate={{ opacity: 1, y: 0 }}
        >
          <div className="text-slate-400 mb-4">
            <svg className="w-16 h-16 mx-auto" viewBox="0 0 24 24" fill="none">
              <path d="M17 21V19C17 17.9391 16.5786 16.9217 15.8284 16.1716C15.0783 15.4214 14.0609 15 13 15H5C3.93913 15 2.92172 15.4214 2.17157 16.1716C1.42143 16.9217 1 17.9391 1 19V21" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/>
              <circle cx="9" cy="7" r="4" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/>
            </svg>
          </div>
          <p className="text-slate-500 text-lg">暂无面试记录</p>
          <p className="text-slate-400 text-sm mt-2">开始一次模拟面试后，记录将显示在这里</p>
        </motion.div>
      ) : (
        <motion.div
          className="bg-white rounded-2xl p-6"
          initial={{ opacity: 0, y: 20 }}
          animate={{ opacity: 1, y: 0 }}
        >
          <div className="space-y-4">
            {interviews.map((interview) => (
              <motion.div
                key={interview.id}
                initial={{ opacity: 0, y: 10 }}
                animate={{ opacity: 1, y: 0 }}
                className="border border-slate-200 rounded-xl p-5 hover:border-primary-300 hover:shadow-md transition-all cursor-pointer group"
                onClick={() => onViewInterview(interview.sessionId, interview.resumeId)}
              >
                <div className="flex items-start justify-between">
                  <div className="flex-1">
                    <div className="flex items-center gap-3 mb-2">
                      <span className="font-semibold text-slate-800">
                        面试 #{interview.id}
                      </span>
                      <span className={`px-2 py-1 rounded text-xs font-medium ${
                        interview.status === 'COMPLETED' 
                          ? 'bg-green-100 text-green-700'
                          : interview.status === 'IN_PROGRESS'
                          ? 'bg-blue-100 text-blue-700'
                          : 'bg-slate-100 text-slate-700'
                      }`}>
                        {interview.status === 'COMPLETED' ? '已完成' : 
                         interview.status === 'IN_PROGRESS' ? '进行中' : '已创建'}
                      </span>
                      {interview.overallScore !== null && (
                        <span className="text-lg font-bold text-primary-600">
                          {interview.overallScore} 分
                        </span>
                      )}
                    </div>
                    <div className="text-sm text-slate-500 space-y-1">
                      <p className="flex items-center gap-2">
                        <span className="font-medium text-slate-700">关联简历:</span>
                        <span className="text-primary-600">{interview.resumeFilename}</span>
                      </p>
                      <p>题目数量: {interview.totalQuestions}</p>
                      <p>创建时间: {formatDateOnly(interview.createdAt)}</p>
                      {interview.completedAt && (
                        <p>完成时间: {formatDateOnly(interview.completedAt)}</p>
                      )}
                    </div>
                  </div>
                  {/* 操作按钮 - 使用图标按钮，悬停时显示 */}
                  <div className="flex items-center gap-2 opacity-0 group-hover:opacity-100 transition-opacity" onClick={(e) => e.stopPropagation()}>
                    {/* 导出按钮 */}
                    <motion.button
                      onClick={(e) => {
                        e.stopPropagation();
                        handleExport(interview.sessionId);
                      }}
                      disabled={exporting === interview.sessionId}
                      className="p-2 text-slate-400 hover:text-primary-500 hover:bg-primary-50 rounded-lg transition-all disabled:opacity-50 disabled:cursor-not-allowed"
                      whileHover={{ scale: 1.05 }}
                      whileTap={{ scale: 0.95 }}
                      title="导出PDF"
                    >
                      {exporting === interview.sessionId ? (
                        <motion.div
                          className="w-5 h-5 border-2 border-primary-500 border-t-transparent rounded-full"
                          animate={{ rotate: 360 }}
                          transition={{ duration: 1, repeat: Infinity, ease: "linear" }}
                        />
                      ) : (
                        <svg className="w-5 h-5" viewBox="0 0 24 24" fill="none">
                          <path d="M21 15V19C21 19.5304 20.7893 20.0391 20.4142 20.4142C20.0391 20.7893 19.5304 21 19 21H5C4.46957 21 3.96086 20.7893 3.58579 20.4142C3.21071 20.0391 3 19.5304 3 19V15" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/>
                          <polyline points="7,10 12,15 17,10" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/>
                          <line x1="12" y1="15" x2="12" y2="3" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/>
                        </svg>
                      )}
                    </motion.button>
                    
                    {/* 删除按钮 */}
                    <button
                      onClick={(e) => handleDeleteClick(interview.sessionId, e)}
                      disabled={deletingSessionId === interview.sessionId}
                      className="p-2 text-slate-400 hover:text-red-500 hover:bg-red-50 rounded-lg transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                      title="删除面试记录"
                    >
                      {deletingSessionId === interview.sessionId ? (
                        <motion.div
                          className="w-5 h-5 border-2 border-red-500 border-t-transparent rounded-full"
                          animate={{ rotate: 360 }}
                          transition={{ duration: 1, repeat: Infinity, ease: "linear" }}
                        />
                      ) : (
                        <svg className="w-5 h-5" viewBox="0 0 24 24" fill="none">
                          <path d="M3 6H5H21M8 6V4C8 3.46957 8.21071 2.96086 8.58579 2.58579C8.96086 2.21071 9.46957 2 10 2H14C14.5304 2 15.0391 2.21071 15.4142 2.58579C15.7893 2.96086 16 3.46957 16 4V6M19 6V20C19 20.5304 18.7893 21.0391 18.4142 21.4142C18.0391 21.7893 17.5304 22 17 22H7C6.46957 22 5.96086 21.7893 5.58579 21.4142C5.21071 21.0391 5 20.5304 5 20V6H19Z" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/>
                          <path d="M10 11V17M14 11V17" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/>
                        </svg>
                      )}
                    </button>
                  </div>
                </div>
              </motion.div>
            ))}
          </div>
        </motion.div>
      )}

      {/* 删除确认对话框 */}
      <DeleteConfirmDialog
        open={deleteConfirm !== null}
        item={deleteConfirm ? { id: 0, sessionId: deleteConfirm.sessionId } : null}
        itemType="面试记录"
        loading={deletingSessionId !== null}
        onConfirm={handleDeleteConfirm}
        onCancel={() => setDeleteConfirm(null)}
      />
    </motion.div>
  );
}

