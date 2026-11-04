import React from "react";
import { useNavigate } from "react-router-dom";
import styles from "./hour.module.scss";
import { durationFormat } from "../../../utils/index";
import { message } from "antd";

interface PropInterface {
  id: number;
  cid: number;
  title: string;
  duration: number;
  record: any;
  progress: number;
  locked?: boolean;
}

export const HourCompenent: React.FC<PropInterface> = ({
  id,
  cid,
  title,
  duration,
  record,
  progress,
  locked = false,
}) => {
  const navigate = useNavigate();
  return (
    <>
      <div
        className={styles["item"]}
        onClick={() => {
          if (locked) {
            message.info("请先学完前面的课时");
            return;
          }
          navigate(`/course/${cid}/hour/${id}`);
        }}
        aria-disabled={locked}
      >
        <div className={styles["left-item"]}>
          <i className="iconfont icon-icon-video"></i>
          <div className={styles["title"]}>
            {title}({durationFormat(Number(duration))})
          </div>
        </div>
        <div className="d-flex">
          {locked && <div className={styles["record"]}>🔒 请先学完前面课时</div>}
          {!locked && progress >= 0 && progress < 100 && (
            <>
              {progress === 0 && <div className={styles["link"]}>未学习 · 开始学习</div>}
              {progress !== 0 && (
                <>
                  <div className={styles["record"]}>
                    学习中 · 上次学习到
                    {durationFormat(Number(record.finished_duration || 0))}
                  </div>
                  <div className={styles["link"]}>继续学习</div>
                </>
              )}
            </>
          )}
          {progress >= 100 && <div className={styles["complete"]}>已学完</div>}
        </div>
      </div>
    </>
  );
};
