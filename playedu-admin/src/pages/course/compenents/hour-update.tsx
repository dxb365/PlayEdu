import React, { useState, useEffect } from "react";
import { Button, Drawer, Form, Input, Modal, message, Spin, Select, Space } from "antd";
import styles from "./hour-update.module.less";
import { course, courseHour, courseChapter } from "../../../api/index";
import { SelectResource } from "../../../compenents";
import { ExclamationCircleFilled } from "@ant-design/icons";
import { TreeHours } from "./hours";

const { confirm } = Modal;

interface PropInterface {
  id: number;
  open: boolean;
  onCancel: () => void;
}

export const CourseHourUpdate: React.FC<PropInterface> = ({
  id,
  open,
  onCancel,
}) => {
  const [form] = Form.useForm();
  const [init, setInit] = useState(true);
  const [chapterType, setChapterType] = useState(0);
  const [chapters, setChapters] = useState<CourseChaptersModel[]>([]);
  const [hours, setHours] = useState<number[]>([]);
  const [chapterHours, setChapterHours] = useState<any>([]);
  const [videoVisible, setVideoVisible] = useState<boolean>(false);
  const [treeData, setTreeData] = useState<CourseHourModel[]>([]);
  const [addvideoCurrent, setAddvideoCurrent] = useState(0);
  const [sortMode, setSortMode] = useState("name:asc");
  const [sortChapter, setSortChapter] = useState<number | undefined>();
  const [sorting, setSorting] = useState(false);

  useEffect(() => {
    setInit(true);
    setSortChapter(undefined);
    if (id === 0) {
      return;
    }
    setVideoVisible(false);
    setChapters([]);
    setHours([]);
    setChapterHours([]);
    setTreeData([]);
    setAddvideoCurrent(0);
    setChapterType(0);
    getDetail();
  }, [id, open]);

  const getDetail = () => {
    return course.course(id).then((res: any) => {
      let chapterType = res.data.chapters.length > 0 ? 1 : 0;
      setChapterType(chapterType);
      if (chapterType === 1) {
        setTreeData([]);
        setHours([]);
        let hours = res.data.hours;
        let chapters = res.data.chapters;
        const arr: any = [];
        const keys: any = [];
        for (let i = 0; i < chapters.length; i++) {
          arr.push({
            id: chapters[i].id,
            name: chapters[i].name,
            hours: resetHours(hours[chapters[i].id]).arr,
          });
          keys.push(resetHours(hours[chapters[i].id]).keys);
        }
        setChapters(arr);
        setChapterHours(keys);
      } else {
        setChapters([]);
        setChapterHours([]);
        let hours = res.data.hours;
        if (JSON.stringify(hours) !== "{}") {
          const arr: any = resetHours(hours[0]).arr;
          const keys: any = resetHours(hours[0]).keys;
          setTreeData(arr);
          setHours(keys);
        } else {
          setTreeData([]);
          setHours([]);
        }
      }
      setInit(false);
    });
  };

  const resetHours = (data: any) => {
    const arr: any = [];
    const keys: any = [];
    if (data) {
      for (let i = 0; i < data.length; i++) {
        arr.push({
          duration: data[i].duration,
          type: data[i].type,
          name: data[i].title,
          rid: data[i].rid,
          id: data[i].id,
        });
        keys.push(data[i].rid);
      }
    }
    return { arr, keys };
  };

  const onFinish = (values: any) => {};

  const onFinishFailed = (errorInfo: any) => {
    console.log("Failed:", errorInfo);
  };

  const selectData = (arr: any, videos: any) => {
    const hours: any = [];
    for (let i = 0; i < videos.length; i++) {
      hours.push({
        chapter_id: 0,
        sort: treeData.length + i,
        title: videos[i].name,
        type: videos[i].type,
        duration: videos[i].duration,
        rid: videos[i].rid,
      });
    }
    if (hours.length === 0) {
      message.error("请选择视频");
      return;
    }
    courseHour.storeCourseHourMulti(id, hours).then((res: any) => {
      console.log("ok");
      setVideoVisible(false);
      getDetail();
    });
  };

  const selectChapterData = (arr: any, videos: any) => {
    const data = [...chapters];
    if (!data[addvideoCurrent].id) {
      message.error("添加课时失败");
      return;
    }
    const hours: any = [];
    for (let i = 0; i < videos.length; i++) {
      hours.push({
        chapter_id: data[addvideoCurrent].id,
        sort: data[addvideoCurrent].hours.length + i,
        title: videos[i].name,
        type: videos[i].type,
        duration: videos[i].duration,
        rid: videos[i].rid,
      });
    }
    if (hours.length === 0) {
      message.error("请选择视频");
      return;
    }
    courseHour.storeCourseHourMulti(id, hours).then((res: any) => {
      console.log("ok");
      setVideoVisible(false);
      getDetail();
    });
  };

  const delHour = (hid: number) => {
    const data = [...treeData];
    confirm({
      title: "操作确认",
      icon: <ExclamationCircleFilled />,
      content: "确认删除此课时？",
      centered: true,
      okText: "确认",
      cancelText: "取消",
      onOk() {
        const index = data.findIndex((i: any) => i.rid === hid);
        let delId = data[index].id;
        if (index >= 0) {
          data.splice(index, 1);
        }
        if (data.length > 0) {
          setTreeData(data);
          const keys = data.map((item: any) => item.rid);
          setHours(keys);
        } else {
          setTreeData([]);
          setHours([]);
        }
        if (delId) {
          courseHour.destroyCourseHour(id, delId).then((res: any) => {
            console.log("ok");
          });
        }
      },
      onCancel() {
        console.log("Cancel");
      },
    });
  };

  const transHour = (arr: any) => {
    setHours(arr);
    const data = [...treeData];
    const newArr: any = [];
    const hourIds: any = [];
    for (let i = 0; i < arr.length; i++) {
      data.map((item: any) => {
        if (item.rid === arr[i]) {
          newArr.push(item);
          hourIds.push(item.id);
        }
      });
    }
    setTreeData(newArr);
    courseHour.transCourseHour(id, hourIds).then((res: any) => {
      console.log("ok");
    });
  };

  const addNewChapter = () => {
    const arr = [...chapters];
    const keys = [...chapterHours];
    arr.push({
      name: "",
      hours: [],
    });
    keys.push([]);
    setChapters(arr);
    setChapterHours(keys);
  };

  const setChapterName = (index: number, value: string) => {
    const arr = [...chapters];
    arr[index].name = value;
    setChapters(arr);
  };

  const saveChapterName = (index: number, value: string) => {
    const arr = [...chapters];
    if (arr[index].id) {
      courseChapter
        .updateCourseChapter(id, Number(arr[index].id), value, index + 1)
        .then((res: any) => {
          console.log("ok");
          getDetail();
        });
    } else {
      courseChapter
        .storeCourseChapter(id, value, arr.length)
        .then((res: any) => {
          console.log("ok");
          getDetail();
        });
    }
  };

  const delChapter = (index: number) => {
    const arr = [...chapters];
    const keys = [...chapterHours];
    confirm({
      title: "操作确认",
      icon: <ExclamationCircleFilled />,
      content: "删除章节会清空已添加课时，确认删除？",
      centered: true,
      okText: "确认",
      cancelText: "取消",
      onOk() {
        if (arr[index].id) {
          courseChapter
            .destroyCourseChapter(id, Number(arr[index].id))
            .then((res: any) => {
              console.log("ok");
              getDetail();
            });
        }
      },
      onCancel() {},
    });
  };

  const delChapterHour = (index: number, hid: number) => {
    const keys = [...chapterHours];
    const data = [...chapters];
    confirm({
      title: "操作确认",
      icon: <ExclamationCircleFilled />,
      content: "确认删除此课时？",
      centered: true,
      okText: "确认",
      cancelText: "取消",
      onOk() {
        const current = data[index].hours.findIndex((i: any) => i.rid === hid);
        let delId = data[index].hours[current].id;
        if (current >= 0) {
          data[index].hours.splice(current, 1);
        }
        if (data[index].hours.length > 0) {
          setChapters(data);
          keys[index] = data[index].hours.map((item: any) => item.rid);
          setChapterHours(keys);
        } else {
          keys[index] = [];
          data[index].hours = [];
          setChapters(data);
          setChapterHours(keys);
        }

        if (delId) {
          courseHour.destroyCourseHour(id, delId).then((res: any) => {
            console.log("ok");
          });
        }
      },
      onCancel() {
        console.log("Cancel");
      },
    });
  };

  const transChapterHour = (index: number, arr: any) => {
    const keys = [...chapterHours];
    keys[index] = arr;
    setChapterHours(keys);

    const data = [...chapters];
    const newArr: any = [];
    const hourIds: any = [];
    for (let i = 0; i < arr.length; i++) {
      data[index].hours.map((item: any) => {
        if (item.rid === arr[i]) {
          newArr.push(item);
          hourIds.push(item.id);
        }
      });
    }
    data[index].hours = newArr;
    setChapters(data);
    courseHour.transCourseHour(id, hourIds).then((res: any) => {
      console.log("ok");
    });
  };

  const changeChapterHours = (arr: any) => {
    const newArr: any = [];
    for (let i = 0; i < arr.length; i++) {
      arr[i].map((item: any) => {
        newArr.push(item);
      });
    }
    return newArr;
  };

  const applySort = () => {
    const [field, order] = sortMode.split(":");
    confirm({
      title: "调整课时顺序",
      content: "确认按所选方式排列并保存？学员将使用新顺序学习，章节顺序保持原值。",
      okText: "排序并保存",
      cancelText: "取消",
      centered: true,
      async onOk() {
        setSorting(true);
        try {
          await courseHour.autoSortCourseHours(id, field, order, sortChapter);
          await getDetail();
          message.success("课时顺序已保存");
        } finally {
          setSorting(false);
        }
      },
    });
  };

  return (
    <>
      {open ? (
        <Drawer
          title="课时管理"
          onClose={onCancel}
          maskClosable={false}
          open={true}
          width={634}
        >
          <div className={styles["top-content"]}>
            <p>1.线上课课时调整及时生效，操作不可逆，请谨慎操作。</p>
            <p>2.课时调整后，已有学习进度会在学员学习时重新计算。</p>
          </div>
          <Space wrap style={{ marginTop: 20, marginLeft: 26 }}>
            {chapterType === 1 && (
              <Select
                aria-label="排序范围"
                style={{ width: 180 }}
                value={sortChapter ?? -1}
                disabled={init || sorting}
                onChange={(value) => setSortChapter(value === -1 ? undefined : value)}
                options={[
                  { value: -1, label: "全部章节（章内排序）" },
                  ...chapters.filter((item) => item.id).map((item, index) => ({
                    value: Number(item.id), label: `章节${index + 1}：${item.name}`,
                  })),
                ]}
              />
            )}
            <Select
              aria-label="课时排序方式"
              style={{ width: 190 }}
              value={sortMode}
              disabled={init || sorting}
              onChange={setSortMode}
              options={[
                { value: "name:asc", label: "名称：升序（小到大）" },
                { value: "name:desc", label: "名称：降序（大到小）" },
                { value: "modified_at:asc", label: "修改时间：从早到晚" },
                { value: "modified_at:desc", label: "修改时间：从晚到早" },
              ]}
            />
            <Button type="primary" loading={sorting}
              disabled={init || (chapterType === 0 ? treeData.length < 2 :
                !chapters.some((item) => (sortChapter === undefined || item.id === sortChapter) && item.hours.length > 1))}
              onClick={applySort}>排序并保存</Button>
          </Space>
          <p style={{ margin: "10px 26px 0", color: "#666" }}>
            名称按数字顺序排列。NAS 视频使用原文件修改时间，上传视频使用资源修改时间。
          </p>
          <div className="float-left mt-24">
            <SelectResource
              defaultKeys={
                chapterType === 0 ? hours : changeChapterHours(chapterHours)
              }
              open={videoVisible}
              onCancel={() => {
                setVideoVisible(false);
              }}
              onSelected={(arr: any, videos: any) => {
                if (chapterType === 0) {
                  selectData(arr, videos);
                } else {
                  selectChapterData(arr, videos);
                }
              }}
            />
            {init && (
              <div className="float-left text-center mt-30">
                <Spin></Spin>
              </div>
            )}
            <div
              className="float-left"
              style={{ display: init ? "none" : "block" }}
            >
              <Form
                form={form}
                name="hour-update-basic"
                labelCol={{ span: 5 }}
                wrapperCol={{ span: 19 }}
                initialValues={{ remember: true }}
                onFinish={onFinish}
                onFinishFailed={onFinishFailed}
                autoComplete="off"
              >
                {chapterType === 0 && (
                  <div className="c-flex">
                    <Form.Item>
                      <div className="ml-42">
                        <Button
                          onClick={() => setVideoVisible(true)}
                          type="primary"
                        >
                          添加课时
                        </Button>
                      </div>
                    </Form.Item>
                    <div className={styles["hous-box"]}>
                      {treeData.length === 0 && (
                        <span className={styles["no-hours"]}>
                          请点击上方按钮添加课时
                        </span>
                      )}
                      {treeData.length > 0 && (
                        <TreeHours
                          data={treeData}
                          onRemoveItem={(id: number) => {
                            delHour(id);
                          }}
                          onUpdate={(arr: any[]) => {
                            transHour(arr);
                          }}
                        />
                      )}
                    </div>
                  </div>
                )}
                {chapterType === 1 && (
                  <div className="c-flex">
                    {chapters.length > 0 &&
                      chapters.map((item: any, index: number) => {
                        return (
                          <div
                            key={item.hours.length + "章节" + index}
                            className={styles["chapter-item"]}
                          >
                            <div className="d-flex">
                              <div className={styles["label"]}>
                                章节{index + 1}：
                              </div>
                              <Input
                                value={item.name}
                                className={styles["input"]}
                                onChange={(e) => {
                                  setChapterName(index, e.target.value);
                                }}
                                onBlur={(e) => {
                                  saveChapterName(index, e.target.value);
                                }}
                                placeholder="请在此处输入章节名称"
                                allowClear
                              />
                              <Button
                                disabled={!item.name}
                                className="mr-16"
                                type="primary"
                                onClick={() => {
                                  setVideoVisible(true);
                                  setAddvideoCurrent(index);
                                }}
                              >
                                添加课时
                              </Button>
                              <Button onClick={() => delChapter(index)}>
                                删除章节
                              </Button>
                            </div>
                            <div className={styles["chapter-hous-box"]}>
                              {item.hours.length === 0 && (
                                <span className={styles["no-hours"]}>
                                  请点击上方按钮添加课时
                                </span>
                              )}
                              {item.hours.length > 0 && (
                                <TreeHours
                                  data={item.hours}
                                  onRemoveItem={(id: number) => {
                                    delChapterHour(index, id);
                                  }}
                                  onUpdate={(arr: any[]) => {
                                    transChapterHour(index, arr);
                                  }}
                                />
                              )}
                            </div>
                          </div>
                        );
                      })}
                    <Form.Item>
                      <div className="ml-42">
                        <Button onClick={() => addNewChapter()}>
                          添加章节
                        </Button>
                      </div>
                    </Form.Item>
                  </div>
                )}
              </Form>
            </div>
          </div>
        </Drawer>
      ) : null}
    </>
  );
};
