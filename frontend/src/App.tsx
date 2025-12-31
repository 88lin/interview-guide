import {BrowserRouter, Navigate, Route, Routes, useLocation, useNavigate, useParams} from 'react-router-dom';
import Layout from './components/Layout';
import UploadPage from './pages/UploadPage';
import HistoryList from './pages/HistoryPage';
import ResumeDetailPage from './pages/ResumeDetailPage';
import Interview from './pages/InterviewPage';
import KnowledgeBaseQueryPage from './pages/KnowledgeBaseQueryPage';
import KnowledgeBaseUploadPage from './pages/KnowledgeBaseUploadPage';
import MarkdownTestPage from './pages/MarkdownTestPage';
import {historyApi} from './api/history';
import {useEffect, useState} from 'react';
import type {ResumeAnalysisResponse, StorageInfo} from './types/resume';
import type {UploadKnowledgeBaseResponse} from './api/knowledgebase';

// 上传页面包装器
function UploadPageWrapper() {
  const navigate = useNavigate();
  
  const handleAnalysisComplete = (_result: ResumeAnalysisResponse, storage: StorageInfo) => {
    // 直接跳转到详情页
    if (storage.resumeId) {
      navigate(`/history/${storage.resumeId}`);
    }
  };
  
  return <UploadPage onAnalysisComplete={handleAnalysisComplete} />;
}

// 历史记录列表包装器
function HistoryListWrapper() {
  const navigate = useNavigate();
  
  const handleSelectResume = (id: number) => {
    navigate(`/history/${id}`);
  };
  
  return <HistoryList onSelectResume={handleSelectResume} />;
}

// 简历详情包装器
function ResumeDetailWrapper() {
  const { resumeId } = useParams<{ resumeId: string }>();
  const navigate = useNavigate();
  
  if (!resumeId) {
    return <Navigate to="/history" replace />;
  }
  
  const handleBack = () => {
    navigate('/history');
  };
  
  const handleStartInterview = (resumeText: string, resumeId: number) => {
    navigate(`/interview/${resumeId}`, { state: { resumeText } });
  };
  
  return (
    <ResumeDetailPage
      resumeId={parseInt(resumeId, 10)}
      onBack={handleBack}
      onStartInterview={handleStartInterview}
    />
  );
}

// 模拟面试包装器
function InterviewWrapper() {
  const { resumeId } = useParams<{ resumeId: string }>();
  const navigate = useNavigate();
  const location = useLocation();
  const [resumeText, setResumeText] = useState<string>('');
  const [loading, setLoading] = useState(true);
  
  useEffect(() => {
    // 优先从location state获取resumeText
    const stateText = (location.state as { resumeText?: string })?.resumeText;
    if (stateText) {
      setResumeText(stateText);
      setLoading(false);
    } else if (resumeId) {
      // 如果没有，从API获取简历详情
      historyApi.getResumeDetail(parseInt(resumeId, 10))
        .then(resume => {
          setResumeText(resume.resumeText);
          setLoading(false);
        })
        .catch(err => {
          console.error('获取简历文本失败', err);
          setLoading(false);
        });
    } else {
      setLoading(false);
    }
  }, [resumeId, location.state]);
  
  if (!resumeId) {
    return <Navigate to="/history" replace />;
  }
  
  const handleBack = () => {
    // 尝试返回详情页，如果失败则返回历史列表
    navigate(`/history/${resumeId}`, { replace: false });
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
    <Interview
      resumeText={resumeText}
      resumeId={parseInt(resumeId, 10)}
      onBack={handleBack}
    />
  );
}

function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<Layout />}>
          {/* 默认重定向到上传页面 */}
          <Route index element={<Navigate to="/upload" replace />} />
          
          {/* 上传页面 */}
          <Route path="upload" element={<UploadPageWrapper />} />
          
          {/* 历史记录列表 */}
          <Route path="history" element={<HistoryListWrapper />} />
          
          {/* 简历详情 */}
          <Route path="history/:resumeId" element={<ResumeDetailWrapper />} />
          
          {/* 模拟面试 */}
          <Route path="interview/:resumeId" element={<InterviewWrapper />} />
          
          {/* 知识库问答 */}
          <Route path="knowledgebase" element={<KnowledgeBaseQueryPageWrapper />} />
          
          {/* 知识库上传 */}
          <Route path="knowledgebase/upload" element={<KnowledgeBaseUploadPageWrapper />} />
          
          {/* Markdown 测试页面 */}
          <Route path="markdown-test" element={<MarkdownTestPage />} />
        </Route>
      </Routes>
    </BrowserRouter>
  );
}

// 知识库问答页面包装器
function KnowledgeBaseQueryPageWrapper() {
  const navigate = useNavigate();
  
  const handleBack = () => {
    navigate('/upload');
  };
  
  const handleUpload = () => {
    navigate('/knowledgebase/upload');
  };
  
  return <KnowledgeBaseQueryPage onBack={handleBack} onUpload={handleUpload} />;
}

// 知识库上传页面包装器
function KnowledgeBaseUploadPageWrapper() {
  const navigate = useNavigate();
  
  const handleUploadComplete = (result: UploadKnowledgeBaseResponse) => {
    // 上传完成后返回问答页面
    navigate('/knowledgebase');
  };
  
  const handleBack = () => {
    navigate('/knowledgebase');
  };
  
  return <KnowledgeBaseUploadPage onUploadComplete={handleUploadComplete} onBack={handleBack} />;
}

export default App;
