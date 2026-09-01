import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import Layout from './components/Layout';
import ChatWorkspacePage from './pages/ChatWorkspacePage';
import AnalysisPage from './pages/AnalysisPage';
import CalibrationPage from './pages/CalibrationPage';
import HistoryPage from './pages/HistoryPage';

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<Layout />}>
          <Route index element={<ChatWorkspacePage />} />
          <Route path="classic" element={<AnalysisPage />} />
          <Route path="history" element={<HistoryPage />} />
          <Route path="calibration" element={<CalibrationPage />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Route>
      </Routes>
    </BrowserRouter>
  );
}
