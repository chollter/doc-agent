import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import Layout from './components/Layout';
import SummaryPage from './pages/SummaryPage';

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<Layout />}>
          <Route index element={<Navigate to="/summary" replace />} />
          <Route path="summary" element={<SummaryPage />} />
          <Route path="*" element={<Navigate to="/summary" replace />} />
        </Route>
      </Routes>
    </BrowserRouter>
  );
}
